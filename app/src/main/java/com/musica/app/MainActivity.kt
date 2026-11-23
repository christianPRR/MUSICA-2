package com.musica.app

import android.Manifest
import java.io.File
import android.content.ComponentName
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Song(val id: Long, val title: String, val artist: String, val durationMs: Long, val item: MediaItem,
                val album: String = "", val genre: String = "", val path: String = "")

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var future: ListenableFuture<MediaController>? = null

    private var crash by mutableStateOf<String?>(null)

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
        if (crash == null) connect()
    }

    override fun onStop() {
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
        val cf = File(filesDir, "crash.txt")
        if (cf.exists()) crash = try { cf.readText() } catch (e: Exception) { "error desconocido" }
        setContent {
            val c = crash
            if (c != null) CrashScreen(c) { cf.delete(); crash = null; connect() } else MusicaApp()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MusicaApp() {
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        val scheme = PowerampScheme
        MaterialTheme(colorScheme = scheme) {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                       else Manifest.permission.READ_EXTERNAL_STORAGE
            var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
            var granted by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                granted = it; if (it) songs = loadSongs()
            }
            LaunchedEffect(Unit) { launcher.launch(perm) }

            var playing by remember { mutableStateOf(false) }
            var currentId by remember { mutableStateOf<String?>(null) }
            var showPlayer by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var showQueue by remember { mutableStateOf(false) }
            var favs by remember { mutableStateOf(Store.favs(ctx)) }
            var playlists by remember { mutableStateOf(Store.playlists(ctx)) }
            var searching by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var dialogSong by remember { mutableStateOf<Song?>(null) }
            var restored by remember { mutableStateOf(false) }
            var reloadTick by remember { mutableIntStateOf(0) }
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
            val current = songs.firstOrNull { it.id.toString() == currentId }
            LaunchedEffect(controller, songs) {
                val c = controller
                if (c != null && songs.isNotEmpty() && !restored && c.mediaItemCount == 0) {
                    restored = true
                    val (ids, idx, pos) = Store.loadResume(ctx)
                    val byId = songs.associateBy { it.id }
                    val items = ids.mapNotNull { byId[it]?.item }
                    if (items.isNotEmpty()) {
                        c.setMediaItems(items, idx.coerceIn(0, items.size - 1), pos)
                        c.prepare()
                        currentId = c.currentMediaItem?.mediaId
                    }
                }
            }
            LaunchedEffect(reloadTick) { if (reloadTick > 0) { delay(1500); songs = loadSongs() } }
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
                addToList = { s -> dialogSong = s })

            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = { TopAppBar(title = { if (searching) OutlinedTextField(query, { query = it }, singleLine = true,
                        placeholder = { Text("Buscar canción, artista o álbum") }, modifier = Modifier.fillMaxWidth()) else Text("MUSICA") },
                    actions = { IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Buscar") }
                        IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "Ajustes") } }) },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp, modifier = Modifier.clickable(enabled = current != null) { showPlayer = true }) {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (current != null) { Art(current, 44.dp); Spacer(Modifier.width(10.dp)) }
                                Text(current?.title ?: "Elige una canción", Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({ controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null) }
                                FilledIconButton({ controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                }
                                IconButton({ controller?.seekToNext() }) { Icon(Icons.Default.SkipNext, null) }
                            }
                        }
                    }
                ) { pad ->
                    if (!granted || songs.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                            Text(if (!granted) "Permite el acceso a tu música" else "No se encontraron canciones")
                        }
                    } else LibraryScreen(songs, playlists, query, actions,
                        { n -> playlists = playlists + (n to emptyList<Long>()); Store.savePlaylists(ctx, playlists) },
                        { n -> playlists = playlists - n; Store.savePlaylists(ctx, playlists) },
                        Modifier.padding(pad)) { list, i ->
                        controller?.run { setMediaItems(list.map { it.item }, i, 0L); prepare(); play() }
                        showPlayer = true
                    }
                }
                if (showPlayer && current != null) {
                    BackHandler { showPlayer = false }
                    PlayerScreen(current, controller, playing, current.id in favs, { actions.toggleFav(current) }, { showQueue = true }, { reloadTick++ }) { showPlayer = false }
                }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(songs, remember { loadSongs(true).map { File(it.path).parent ?: "" }.distinct().sorted() },
                        { reloadTick++ }) { showSettings = false }
                }
                if (showQueue) {
                    BackHandler { showQueue = false }
                    QueueScreen(controller) { showQueue = false }
                }
                dialogSong?.let { ds ->
                    AlertDialog(onDismissRequest = { dialogSong = null }, title = { Text("Añadir a lista") },
                        text = {
                            if (playlists.isEmpty()) Text("Crea una lista en la pestaña LISTAS")
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
        return out
    }
}
