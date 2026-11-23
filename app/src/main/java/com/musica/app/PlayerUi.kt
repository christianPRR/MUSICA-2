package com.musica.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val White60 = Color.White.copy(alpha = 0.6f)
private val Accent = Color(0xFFFF9800)

private fun clock(ms: Long): String {
    val t = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun PlayerScreen(song: Song, c: MediaController?, playing: Boolean, isFav: Boolean, onFav: () -> Unit,
                 onQueue: () -> Unit, onReload: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var showLyrics by remember { mutableStateOf(false) }

    fun setLyrics(t: String?) {
        lyrics = t?.let { Repo.parse(it) }
        status = if (t == null) "No se encontró letra" else ""
    }
    LaunchedEffect(song.id) {
        cover = Repo.loadCover(ctx, song) ?: Repo.downloadCover(ctx, song)
        setLyrics(Repo.loadLyrics(ctx, song, false))
    }
    LaunchedEffect(song.id) { quality = Repo.quality(ctx, song) }
    LaunchedEffect(c) { while (true) { pos = c?.currentPosition ?: 0L; delay(300) } }
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

    val dur = song.durationMs.coerceAtLeast(1L)
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val onSeek: (Long) -> Unit = { pos = it; c?.seekTo(it) }

    val shuffleBtn: @Composable () -> Unit = {
        IconButton({ c?.shuffleModeEnabled = !shuffle }) {
            Icon(Icons.Default.Shuffle, "Aleatorio", tint = if (shuffle) Accent else White60)
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
                tint = if (repeat != Player.REPEAT_MODE_OFF) Accent else White60)
        }
    }
    val favBtn: @Composable () -> Unit = {
        IconButton(onFav) {
            Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorita",
                tint = if (isFav) Accent else White60)
        }
    }
    val queueBtn: @Composable () -> Unit = {
        IconButton(onQueue) { Icon(Icons.Default.QueueMusic, "Cola", tint = White60) }
    }
    val prevBtn: @Composable () -> Unit = {
        IconButton({ c?.seekToPrevious() }, Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, "Anterior", Modifier.size(40.dp), tint = Color.White)
        }
    }
    val playBtn: @Composable () -> Unit = {
        IconButton({ c?.let { if (it.isPlaying) it.pause() else it.play() } }, Modifier.size(76.dp)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir", Modifier.size(60.dp), tint = Color.White)
        }
    }
    val nextBtn: @Composable () -> Unit = {
        IconButton({ c?.seekToNext() }, Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, "Siguiente", Modifier.size(40.dp), tint = Color.White)
        }
    }
    val moreMenu: @Composable () -> Unit = {
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Color.White) }
            DropdownMenu(menu, { menu = false }) {
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Fondo estilo iPhone: la portada desenfocada con un velo oscuro
        val b = cover
        if (b != null) Image(b.asImageBitmap(), null,
            Modifier.fillMaxSize().blur(80.dp, BlurredEdgeTreatment.Unbounded), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A2A6E), Color.Black))))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Accent)

        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (land) {
                // Horizontal: portada a la izquierda, letra a la derecha
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                PlayerCover(cover, playing, Modifier.size(minOf(maxWidth, maxHeight)))
                            }
                            TitleBlock(song, quality, true, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        }
                        LyricsView(lyrics, status, pos, c, 26, Modifier.weight(1f).fillMaxHeight())
                    }
                    SeekRow(pos, dur, onSeek)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", tint = Color.White) }
                        shuffleBtn(); prevBtn(); playBtn(); nextBtn(); repeatBtn(); favBtn(); queueBtn(); moreMenu()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp), tint = Color.White) }
                        Spacer(Modifier.weight(1f))
                        IconButton({ showLyrics = !showLyrics }) {
                            Icon(Icons.Default.Subject, "Letra", tint = if (showLyrics) Accent else Color.White)
                        }
                        moreMenu()
                    }
                    if (showLyrics) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlayerCover(cover, true, Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            TitleBlock(song, "", false, Modifier.weight(1f))
                        }
                        LyricsView(lyrics, status, pos, c, 28, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PlayerCover(cover, playing, Modifier.size(minOf(maxWidth, maxHeight)))
                        }
                        TitleBlock(song, quality, true, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp))
                    }
                    SeekRow(pos, dur, onSeek)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) { prevBtn(); playBtn(); nextBtn() }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        shuffleBtn(); repeatBtn(); favBtn(); queueBtn()
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
private fun PlayerCover(b: Bitmap?, playing: Boolean, modifier: Modifier) {
    val s by animateFloatAsState(if (playing) 1f else 0.86f, label = "scale")
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.graphicsLayer { scaleX = s; scaleY = s }.shadow(24.dp, shape).clip(shape)
        .background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = Color.White)
    }
}

@Composable
private fun TitleBlock(song: Song, quality: String, tech: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, fontSize = 17.sp, color = White60, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tech && quality.isNotEmpty()) Text(quality, fontSize = 12.sp, color = Accent)
        if (tech && AudioFx.decoder.isNotEmpty())
            Text("Decodificador: ${AudioFx.decoder}", fontSize = 11.sp, color = White60)
    }
}

@Composable
private fun SeekRow(pos: Long, dur: Long, onSeek: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Slider((pos.toFloat() / dur).coerceIn(0f, 1f), { onSeek((it * dur).toLong()) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(clock(pos), fontSize = 12.sp, color = White60)
            Spacer(Modifier.weight(1f))
            Text("-" + clock(dur - pos), fontSize = 12.sp, color = White60)
        }
    }
}

@Composable
private fun LyricsView(l: Lyrics?, status: String, pos: Long, c: MediaController?, big: Int, modifier: Modifier) {
    if (l == null || l.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(status, color = White60) }
        return
    }
    val idx = if (l.synced) l.lines.indexOfLast { it.first <= pos } else -1
    val state = rememberLazyListState()
    LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem((idx - 1).coerceAtLeast(0)) }
    LazyColumn(modifier, state = state) {
        itemsIndexed(l.lines) { i, line ->
            val target = if (!l.synced) 0.9f else if (i == idx) 1f else 0.4f
            val a by animateFloatAsState(target, label = "lyric")
            Text(line.second,
                Modifier.fillMaxWidth().clickable(enabled = l.synced) { c?.seekTo(line.first) }.padding(vertical = 8.dp),
                color = Color.White.copy(alpha = a),
                fontSize = (if (l.synced) big else big - 6).sp, fontWeight = FontWeight.Bold)
        }
    }
}
