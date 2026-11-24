package com.musica.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val White60 = Color.White.copy(alpha = 0.6f)

private fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun PlayerScreen(song: Song, c: MediaController?, playing: Boolean, active: Boolean, isFav: Boolean,
                 onFav: () -> Unit, onQueue: () -> Unit, onReload: () -> Unit, onArtist: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AudioFx.prefs(ctx) }
    val accent = MaterialTheme.colorScheme.primary
    var cover by remember(song.id) { mutableStateOf<Bitmap?>(null) }
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var status by remember(song.id) { mutableStateOf("Buscando letra…") }
    var busy by remember(song.id) { mutableStateOf(false) }
    var quality by remember(song.id) { mutableStateOf("") }
    var pos by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(c?.shuffleModeEnabled ?: false) }
    var repeat by remember { mutableIntStateOf(c?.repeatMode ?: 0) }
    var showSleep by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(c?.playbackParameters?.speed ?: 1f) }
    var menu by remember { mutableStateOf(false) }
    var karaoke by remember { mutableStateOf(false) }
    var viz by remember { mutableIntStateOf(prefs.getInt("viz", 0)) }
    var pendingViz by remember { mutableIntStateOf(0) }

    val vizPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { viz = pendingViz; prefs.edit().putInt("viz", viz).apply() }
    }
    fun chooseViz(v: Int) {
        if (v == 0 || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viz = v; prefs.edit().putInt("viz", v).apply()
        } else { pendingViz = v; vizPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    fun setLyrics(t: String?) {
        lyrics = t?.let { Lrc.parse(it) }
        status = if (t == null) "No se encontró letra" else ""
    }
    LaunchedEffect(song.id) {
        val b = Repo.bigThumb(ctx, song) ?: Repo.downloadCover(ctx, song)
        cover = b
        if (b != null) ThemeState.cover = withContext(Dispatchers.Default) { Palette.extract(b) }
    }
    LaunchedEffect(song.id) { setLyrics(Repo.loadLyrics(ctx, song, false)) }
    LaunchedEffect(song.id) { quality = Repo.quality(ctx, song) }
    LaunchedEffect(c, playing, active) {
        while (true) { pos = c?.currentPosition ?: 0L; delay(if (active && playing) 250L else 1000L) }
    }
    DisposableEffect(c) {
        shuffle = c?.shuffleModeEnabled ?: false
        repeat = c?.repeatMode ?: 0
        val l = object : Player.Listener {
            override fun onShuffleModeEnabledChanged(e: Boolean) { shuffle = e }
            override fun onRepeatModeChanged(m: Int) { repeat = m }
        }
        c?.addListener(l)
        onDispose { c?.removeListener(l) }
    }
    // El visualizador solo corre con la pantalla visible, la app en primer plano y música sonando
    DisposableEffect(viz, playing, active) {
        if (viz > 0 && playing && active) Viz.start(AudioFx.player?.audioSessionId ?: 0)
        onDispose { Viz.stop() }
    }

    val dur = (c?.duration?.takeIf { it > 0 } ?: song.durationMs).coerceAtLeast(1L)
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val onSeek: (Long) -> Unit = { pos = it; c?.seekTo(it) }

    val shuffleBtn: @Composable () -> Unit = {
        IconButton({ c?.shuffleModeEnabled = !shuffle }) {
            Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (shuffle) accent else White60)
        }
    }
    val repeatBtn: @Composable () -> Unit = {
        IconButton({
            c?.repeatMode = when (repeat) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }) {
            Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repetir",
                tint = if (repeat != Player.REPEAT_MODE_OFF) accent else White60)
        }
    }
    val favBtn: @Composable () -> Unit = {
        IconButton(onFav) {
            Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorita",
                tint = if (isFav) accent else White60)
        }
    }
    val queueBtn: @Composable () -> Unit = {
        IconButton(onQueue) { Icon(Icons.Default.QueueMusic, "Cola", tint = White60) }
    }
    val prevBtn: @Composable () -> Unit = {
        IconButton({ c?.seekToPreviousMediaItem() }, Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, "Anterior", Modifier.size(40.dp), tint = Color.White)
        }
    }
    val playBtn: @Composable () -> Unit = {
        IconButton({ c?.let { if (it.isPlaying) it.pause() else it.play() } }, Modifier.size(80.dp)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir", Modifier.size(64.dp), tint = Color.White)
        }
    }
    val nextBtn: @Composable () -> Unit = {
        IconButton({ c?.seekToNextMediaItem() }, Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, "Siguiente", Modifier.size(40.dp), tint = Color.White)
        }
    }
    val moreMenu: @Composable () -> Unit = {
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Color.White) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Sobre el artista") }, onClick = { menu = false; onArtist() })
                DropdownMenuItem(text = { Text("Actualizar portada") }, onClick = {
                    menu = false
                    scope.launch { busy = true; Repo.downloadCover(ctx, song)?.let { cover = it }; busy = false }
                })
                DropdownMenuItem(text = { Text("Actualizar letra") }, onClick = {
                    menu = false
                    scope.launch { busy = true; status = "Buscando letra…"; setLyrics(Repo.loadLyrics(ctx, song, true)); busy = false }
                })
                DropdownMenuItem(text = { Text("Editar etiquetas") }, onClick = { menu = false; showEdit = true })
                DropdownMenuItem(text = {
                    Text("Visualizador: " + when (viz) { 1 -> "barras"; 2 -> "onda"; else -> "apagado" })
                }, onClick = { menu = false; chooseViz((viz + 1) % 3) })
                DropdownMenuItem(text = {
                    val left = AudioFx.sleepEnd - System.currentTimeMillis()
                    Text(if (left > 0) "Temporizador (${left / 60000 + 1} min)" else "Temporizador para dormir")
                }, onClick = { menu = false; showSleep = true })
                DropdownMenuItem(text = { Text("Velocidad: ${speed}x") }, onClick = {
                    menu = false
                    speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }
                    c?.setPlaybackSpeed(speed)
                })
            }
        }
    }

    // Fondo: color de la portada + la portada reducida y desenfocada
    val tint by animateColorAsState(ThemeState.tint(), tween(700), label = "tint")
    val soft = remember(cover) { cover?.let { Palette.soft(it) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Color.Black))))
        Crossfade(soft, animationSpec = tween(600), label = "bg") { b ->
            if (b != null) Image(b.asImageBitmap(), null,
                Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
                contentScale = ContentScale.Crop, alpha = 0.5f, filterQuality = FilterQuality.Medium)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = accent)

        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (land) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            GestureBox(c, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    PlayerCover(cover, playing, viz > 0, Modifier.size(minOf(maxWidth, maxHeight)))
                                }
                            }
                            TitleRow(song, quality, true, onArtist, Modifier.fillMaxWidth().padding(vertical = 4.dp), favBtn)
                        }
                        KaraokeView(lyrics, status, c, playing, 26, Modifier.weight(1f).fillMaxHeight())
                    }
                    SeekRow({ pos }, dur, onSeek)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", tint = Color.White) }
                        shuffleBtn(); prevBtn(); playBtn(); nextBtn(); repeatBtn(); queueBtn(); moreMenu()
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().safeDrawingPadding().padding(horizontal = 24.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp), tint = Color.White) }
                            Spacer(Modifier.weight(1f))
                            if (karaoke) Text("KARAOKE", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            IconButton({ karaoke = !karaoke }) {
                                Icon(Icons.Default.Subject, "Karaoke", tint = if (karaoke) accent else Color.White)
                            }
                            moreMenu()
                        }
                        if (karaoke) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayerCover(cover, true, false, Modifier.size(56.dp))
                                Spacer(Modifier.width(12.dp))
                                TitleRow(song, "", false, onArtist, Modifier.weight(1f), favBtn)
                            }
                            KaraokeView(lyrics, status, c, playing, 30, Modifier.weight(1f).fillMaxWidth())
                        } else {
                            GestureBox(c, Modifier.weight(1f).fillMaxWidth()) {
                                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    PlayerCover(cover, playing, viz > 0, Modifier.size(minOf(maxWidth, maxHeight, 520.dp)))
                                }
                            }
                            if (viz > 0) VisualizerView(viz, accent, Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp))
                            TitleRow(song, quality, true, onArtist, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), favBtn)
                        }
                        SeekRow({ pos }, dur, onSeek)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) { prevBtn(); playBtn(); nextBtn() }
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            shuffleBtn(); repeatBtn(); queueBtn()
                        }
                    }
                }
            }
        }
    }

    if (showEdit) TagEditorDialog(song, { showEdit = false; onReload() }) { showEdit = false }
    if (showSleep) AlertDialog(onDismissRequest = { showSleep = false }, title = { Text("Temporizador para dormir") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    TextButton({ AudioFx.setSleep(m); showSleep = false }) { Text("$m minutos") }
                }
                TextButton({ AudioFx.setSleep(0); showSleep = false }) { Text("Desactivar") }
            }
        },
        confirmButton = { TextButton({ showSleep = false }) { Text("Cerrar") } })
}

@Composable
private fun PlayerCover(b: Bitmap?, playing: Boolean, reactive: Boolean, modifier: Modifier) {
    val s by animateFloatAsState(if (playing) 1f else 0.88f,
        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow), label = "scale")
    val shape = RoundedCornerShape(28.dp)
    Box(modifier
        .graphicsLayer {
            val k = s * (1f + (if (reactive) Viz.bass * 0.035f else 0f))
            scaleX = k; scaleY = k
        }
        .shadow(28.dp, shape).clip(shape)
        .background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Crossfade(b, animationSpec = tween(450), label = "cover") { bmp ->
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = Color.White)
        }
    }
}

@Composable
private fun TitleRow(song: Song, quality: String, tech: Boolean, onArtist: () -> Unit, modifier: Modifier,
                     trailing: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(song.title to song.artist,
                transitionSpec = {
                    (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) togetherWith fadeOut(tween(200))
                }, label = "title") { (t, a) ->
                Column {
                    Text(t, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a, fontSize = 17.sp, color = White60, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onArtist() })
                }
            }
            if (tech && quality.isNotEmpty()) Text(quality, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            if (tech && AudioFx.decoder.isNotEmpty())
                Text("Decodificador: ${AudioFx.decoder}", fontSize = 11.sp, color = White60)
        }
        trailing()
    }
}

@Composable
private fun SeekRow(pos: () -> Long, dur: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val p = pos()
    Column(Modifier.fillMaxWidth()) {
        Slider(drag ?: (p.toFloat() / dur).coerceIn(0f, 1f), { drag = it },
            onValueChangeFinished = { drag?.let { onSeek((it * dur).toLong()) }; drag = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(clock(drag?.let { (it * dur).toLong() } ?: p), fontSize = 12.sp, color = White60)
            Spacer(Modifier.weight(1f))
            Text("-" + clock(dur - (drag?.let { (it * dur).toLong() } ?: p)), fontSize = 12.sp, color = White60)
        }
    }
}
