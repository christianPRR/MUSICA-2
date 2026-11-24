package com.musica.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Song(val id: Long, val title: String, val artist: String, val durationMs: Long, val item: MediaItem,
                val album: String = "", val genre: String = "", val path: String = "")

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var future: ListenableFuture<MediaController>? = null
    private var crash by mutableStateOf<String?>(null)
    private var foreground by mutableStateOf(false)

    private fun connect() {
        if (future != null) return
        try {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val f = MediaController.Builder(this, token).buildAsync()
            future = f
            f.addListener({ try { controller = f.get() } catch (e: Exception) { } }, { it.run() })
        } catch (e: Exception) { }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (crash == null) connect()
    }

    override fun onStop() {
        foreground = false
        Viz.stop()
        controller?.let { c ->
            if (c.mediaItemCount > 0) Store.saveResume(this,
                (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() },
                c.currentMediaItemIndex, c.currentPosition)
        }
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        ThemeState.load(this)
        val cf = File(filesDir, "crash.txt")
        if (cf.exists()) crash = try { cf.readText() } catch (e: Exception) { "error desconocido" }
        setContent {
            MusicaTheme {
                val c = crash
                if (c != null) CrashScreen(c) { cf.delete(); crash = null; connect() } else MusicaApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MusicaApp() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
        var granted by remember { mutableStateOf(false) }
        fun reload() { scope.launch { songs = withContext(Dispatchers.IO) { loadSongs() } } }

        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                   else Manifest.permission.READ_EXTERNAL_STORAGE
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            granted = r[perm] == true
            if (granted) reload()
        }
        LaunchedEffect(Unit) {
            launcher.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(perm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(perm))
        }

        var playing by remember { mutableStateOf(false) }
        var currentId by remember { mutableStateOf<String?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var showPlayer by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        var showCar by remember { mutableStateOf(false) }
        var artistOpen by remember { mutableStateOf<String?>(null) }
        var favs by remember { mutableStateOf(Store.favs(ctx)) }
        var playlists by remember { mutableStateOf(Store.playlists(ctx)) }
        var searching by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var dialogSong by remember { mutableStateOf<Song?>(null) }
        var restored by remember { mutableStateOf(false) }
        var reloadTick by remember { mutableIntStateOf(0) }
        var backupMsg by remember { mutableStateOf("") }
        var folders by remember { mutableStateOf<List<String>>(emptyList()) }
        var m3uName by remember { mutableStateOf<String?>(null) }
        var bottomH by remember { mutableStateOf(0.dp) }

        DisposableEffect(controller) {
            val c = controller
            playing = c?.isPlaying ?: false
            currentId = c?.currentMediaItem?.mediaId
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { playing = p }
                override fun onMediaItemTransition(m: MediaItem?, r: Int) { currentId = m?.mediaId }
            }
            c?.addListener(l)
            onDispose { c?.removeListener(l) }
        }
        val byId = remember(songs) { songs.associateBy { it.id.toString() } }
        val current = currentId?.let { byId[it] }
        val keepSong = remember { arrayOfNulls<Song>(1) }
        if (current != null) keepSong[0] = current
        val keepArtist = remember { arrayOfNulls<String>(1) }
        if (artistOpen != null) keepArtist[0] = artistOpen

        // Colores dinámicos: se extraen de la portada de la canción que suena
        LaunchedEffect(current?.id) {
            val s = current
            if (s == null) ThemeState.cover = null
            else {
                val b = Repo.thumb(ctx, s) ?: Repo.loadCover(ctx, s)
                if (b != null) ThemeState.cover = withContext(Dispatchers.Default) { Palette.extract(b) }
            }
        }
        LaunchedEffect(controller, songs) {
            val c = controller
            if (c != null && songs.isNotEmpty() && !restored && c.mediaItemCount == 0) {
                restored = true
                val (ids, idx, pos) = Store.loadResume(ctx)
                val byLong = songs.associateBy { it.id }
                val items = ids.mapNotNull { byLong[it]?.item }
                if (items.isNotEmpty()) {
                    c.setMediaItems(items, idx.coerceIn(0, items.size - 1), pos)
                    c.prepare()
                    currentId = c.currentMediaItem?.mediaId
                }
            }
        }
        LaunchedEffect(reloadTick) { if (reloadTick > 0) { delay(1500); songs = withContext(Dispatchers.IO) { loadSongs() } } }
        LaunchedEffect(showSettings) {
            if (showSettings) folders = withContext(Dispatchers.IO) { loadSongs(true).map { File(it.path).parent ?: "" }.distinct().sorted() }
        }

        val startPlay: (List<Song>, Int) -> Unit = { list, i ->
            controller?.run { setMediaItems(list.map { it.item }, i, 0L); prepare(); play() }
        }
        val playAndOpen: (List<Song>, Int) -> Unit = { list, i -> startPlay(list, i); showPlayer = true }
        val openArtist: (String) -> Unit = { a ->
            if (a.isNotBlank() && !a.contains("unknown", true) && a != "Desconocido") artistOpen = a
        }
        val actions = SongActions(favs,
            playNext = { s -> controller?.let { c ->
                if (c.mediaItemCount == 0) { c.setMediaItem(s.item); c.prepare() }
                else c.addMediaItem(c.currentMediaItemIndex + 1, s.item) } },
            addQueue = { s -> controller?.let { c ->
                if (c.mediaItemCount == 0) { c.setMediaItem(s.item); c.prepare() }
                else c.addMediaItem(s.item) } },
            toggleFav = { s ->
                favs = if (s.id in favs) favs - s.id else favs + s.id
                Store.saveFavs(ctx, favs) },
            addToList = { s -> dialogSong = s },
            onArtist = { s -> openArtist(s.artist) })

        // Respaldo y restauración (el usuario elige dónde guardar el archivo)
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                backupMsg = withContext(Dispatchers.IO) {
                    try {
                        val txt = Backup.export(ctx, songs)
                        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(txt.toByteArray(Charsets.UTF_8)) }
                        "Copia guardada."
                    } catch (e: Exception) { "No se pudo guardar la copia." }
                }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                val r = withContext(Dispatchers.IO) {
                    try {
                        val t = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                        Backup.import(ctx, t, songs)
                    } catch (e: Exception) { null }
                }
                backupMsg = r?.summary() ?: "El archivo no es una copia válida de Música."
                if (r != null) { favs = Store.favs(ctx); playlists = Store.playlists(ctx); reloadTick++ }
            }
        }
        val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            val n = m3uName
            if (uri != null && n != null) scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val list = (playlists[n] ?: emptyList()).mapNotNull { id -> songs.firstOrNull { it.id == id } }
                        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.m3u(n, list).toByteArray(Charsets.UTF_8)) }
                    } catch (e: Exception) { }
                }
            }
        }

        val bg = MaterialTheme.colorScheme.background
        val glow by animateColorAsState(ThemeState.cover?.tint ?: Color.Transparent, tween(700), label = "glow")

        BackHandler(enabled = tab != 0 || searching) { if (searching) { searching = false; query = "" } else tab = 0 }

        Box(Modifier.fillMaxSize().background(bg)) {
            Box(Modifier.fillMaxWidth().height(280.dp).background(Brush.verticalGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent))))

            CompositionLocalProvider(LocalBottomPad provides bottomH) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (searching) OutlinedTextField(query, { query = it }, singleLine = true,
                            placeholder = { Text("Buscar canción, artista o álbum") },
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f))
                        else Text(listOf("Música", "Biblioteca", "Estadísticas")[tab],
                            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Buscar") }
                        IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "Ajustes") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (!granted || songs.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (!granted) "Permite el acceso a tu música" else "No se encontraron canciones",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else Crossfade(if (searching) 1 else tab, animationSpec = tween(250), label = "tabs") { t ->
                            when (t) {
                                0 -> HomeScreen(songs, favs, startPlay, openArtist, { tab = 2 }, { showCar = true })
                                1 -> LibraryScreen(songs, playlists, query, actions,
                                    { n -> playlists = playlists + (n to emptyList<Long>()); Store.savePlaylists(ctx, playlists) },
                                    { n -> playlists = playlists - n; Store.savePlaylists(ctx, playlists) },
                                    { n -> m3uName = n; m3uLauncher.launch("$n.m3u") },
                                    openArtist, Modifier.fillMaxSize(), playAndOpen)
                                else -> StatsScreen(songs, startPlay, openArtist)
                            }
                        }
                    }
                }
            }

            // Barra inferior: reproducción en miniatura (cristal) + navegación
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomH = with(density) { it.height.toDp() } }
                .background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.92f), bg)))) {
                val s = keepSong[0]
                if (s != null && current != null) MiniPlayer(s, controller, playing) { showPlayer = true }
                val primary = MaterialTheme.colorScheme.primary
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                val navColors = NavigationBarItemDefaults.colors(selectedIconColor = primary, selectedTextColor = primary,
                    indicatorColor = primary.copy(alpha = 0.16f), unselectedIconColor = muted, unselectedTextColor = muted)
                NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    NavigationBarItem(!searching && tab == 0, { tab = 0; searching = false; query = "" },
                        { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") }, colors = navColors)
                    NavigationBarItem(searching || tab == 1, { tab = 1 },
                        { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Biblioteca") }, colors = navColors)
                    NavigationBarItem(!searching && tab == 2, { tab = 2; searching = false; query = "" },
                        { Icon(Icons.Default.BarChart, null) }, label = { Text("Estadísticas") }, colors = navColors)
                }
            }

            // Pantallas superpuestas con transiciones
            val slideIn = slideInVertically(tween(380)) { it } + fadeIn(tween(300))
            val slideOut = slideOutVertically(tween(300)) { it } + fadeOut(tween(250))

            AnimatedVisibility(showSettings, enter = slideIn, exit = slideOut) {
                BackHandler { showSettings = false }
                SettingsScreen(songs, folders, backupMsg,
                    { exportLauncher.launch("musica-respaldo.json") },
                    { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    { reloadTick++ }) { showSettings = false }
            }
            AnimatedVisibility(showCar, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                BackHandler { showCar = false }
                CarScreen(controller, playing, current ?: keepSong[0], songs, favs, playlists, startPlay) { showCar = false }
            }
            AnimatedVisibility(showPlayer && current != null, enter = slideIn, exit = slideOut) {
                val s = keepSong[0]
                if (s != null) {
                    BackHandler { showPlayer = false }
                    PlayerScreen(s, controller, playing, foreground, s.id in favs,
                        { actions.toggleFav(s) }, { showQueue = true }, { reloadTick++ },
                        { openArtist(s.artist) }) { showPlayer = false }
                }
            }
            AnimatedVisibility(showQueue, enter = slideIn, exit = slideOut) {
                BackHandler { showQueue = false }
                QueueScreen(controller, songs, favs,
                    { name, ids -> playlists = playlists + (name to ids); Store.savePlaylists(ctx, playlists) }) { showQueue = false }
            }
            AnimatedVisibility(artistOpen != null, enter = slideIn, exit = slideOut) {
                BackHandler { artistOpen = null }
                ArtistScreen(keepArtist[0] ?: "", songs, startPlay) { artistOpen = null }
            }

            dialogSong?.let { ds ->
                AlertDialog(onDismissRequest = { dialogSong = null }, title = { Text("Añadir a lista") },
                    text = {
                        if (playlists.isEmpty()) Text("Crea una lista en Biblioteca › Listas")
                        else Column {
                            playlists.keys.forEach { n ->
                                TextButton({
                                    playlists = playlists + (n to (playlists[n].orEmpty() + ds.id))
                                    Store.savePlaylists(ctx, playlists); dialogSong = null
                                }) { Text(n) }
                            }
                        }
                    },
                    confirmButton = { TextButton({ dialogSong = null }) { Text("Cerrar") } })
            }
        }
    }

    private fun loadSongs(all: Boolean = false): List<Song> {
        val p = AudioFx.prefs(this)
        val hidden = p.getStringSet("hidden_dirs", emptySet()) ?: emptySet()
        val min30 = p.getBoolean("min30", false)
        val out = mutableListOf<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA)
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.GENRE)
        try {
            contentResolver.query(uri, cols.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC}!=0", null,
                "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val t = c.getString(1) ?: "Sin título"
                    val a = c.getString(2) ?: "Desconocido"; val d = c.getLong(3)
                    val al = c.getString(4) ?: ""; val path = c.getString(6) ?: ""
                    if (!all) {
                        if ((File(path).parent ?: "") in hidden) continue
                        if (min30 && d < 30000) continue
                    }
                    val genre = if (cols.size > 7) c.getString(7) ?: "" else ""
                    val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(5))
                    val item = MediaItem.Builder().setMediaId(id.toString())
                        .setUri(ContentUris.withAppendedId(uri, id))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(t).setArtist(a)
                            .setAlbumTitle(al).setArtworkUri(art).build()).build()
                    out.add(Song(id, t, a, d, item, al, genre, path))
                }
            }
        } catch (e: SecurityException) { }
        return out
    }
}

/** Reproducción en miniatura: cristal translúcido, desliza a los lados para cambiar de canción o hacia arriba para abrirla. */
@Composable
private fun MiniPlayer(song: Song, c: MediaController?, playing: Boolean, onOpen: () -> Unit) {
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c, playing) { while (true) { pos = c?.currentPosition ?: 0L; delay(if (playing) 1000L else 3000L) } }
    val dur = (c?.duration?.takeIf { it > 0 } ?: song.durationMs).coerceAtLeast(1L)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()
        .glass(RoundedCornerShape(22.dp), 0.10f).swipeSongs(c, onOpen).clickable { onOpen() }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(song, 46.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            AnimatedContent(song.title to song.artist,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.weight(1f), label = "mini") { (t, a) ->
                Column {
                    Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton({ c?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir o pausar", Modifier.size(30.dp))
            }
            IconButton({ c?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
        }
        LinearProgressIndicator(progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
    }
}
