package com.musica.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

/**
 * Modo automóvil: fondo negro, letra grande, botones enormes y nada que obligue a leer o escribir.
 * Sin portada animada, sin letras, sin menús ni búsqueda, para distraer lo menos posible.
 */
@Composable
fun CarScreen(c: MediaController?, playing: Boolean, current: Song?, songs: List<Song>, favs: Set<Long>,
              playlists: Map<String, List<Long>>, onPlay: (List<Song>, Int) -> Unit, onClose: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var panel by remember { mutableIntStateOf(0) }     // 0 principal, 1 favoritas, 2 listas
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c) { while (true) { pos = c?.currentPosition ?: 0L; delay(500) } }
    val accent = MaterialTheme.colorScheme.primary

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF222222))
                    .clickable { if (panel == 0) onClose() else panel = 0 }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, if (panel == 0) "Salir del modo auto" else "Volver", Modifier.size(36.dp), tint = Color.White)
                }
                Spacer(Modifier.size(16.dp))
                Text(when (panel) { 1 -> "Favoritas"; 2 -> "Listas"; else -> "Modo auto" },
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            when (panel) {
                1 -> {
                    val favSongs = remember(songs, favs) { songs.filter { it.id in favs } }
                    if (favSongs.isEmpty()) BigText("Aún no tienes favoritas")
                    else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(favSongs) { i, s ->
                            BigRow(s.title, s.artist) { onPlay(favSongs, i); panel = 0 }
                        }
                    }
                }
                2 -> {
                    if (playlists.isEmpty()) BigText("Aún no tienes listas")
                    else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val entries = playlists.entries.toList()
                        itemsIndexed(entries) { _, e ->
                            val list = e.value.mapNotNull { id -> songs.firstOrNull { it.id == id } }
                            BigRow(e.key, "${list.size} canciones") { if (list.isNotEmpty()) { onPlay(list, 0); panel = 0 } }
                        }
                    }
                }
                else -> {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(current?.title ?: "Elige una canción", color = Color.White, fontSize = 30.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(current?.artist ?: "", color = Color(0xFFBBBBBB), fontSize = 22.sp, textAlign = TextAlign.Center,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(24.dp))
                        val dur = (current?.durationMs ?: 1L).coerceAtLeast(1L)
                        LinearProgressIndicator(progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = accent, trackColor = Color(0xFF2A2A2A))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ c?.seekToPreviousMediaItem() }, Modifier.size(110.dp)) {
                            Icon(Icons.Default.SkipPrevious, "Anterior", Modifier.size(80.dp), tint = Color.White)
                        }
                        Box(Modifier.size(132.dp).clip(CircleShape).background(accent)
                            .clickable { c?.let { if (it.isPlaying) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir o pausar",
                                Modifier.size(84.dp), tint = Color.Black)
                        }
                        IconButton({ c?.seekToNextMediaItem() }, Modifier.size(110.dp)) {
                            Icon(Icons.Default.SkipNext, "Siguiente", Modifier.size(80.dp), tint = Color.White)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BigButton("Favoritas", Modifier.weight(1f)) { panel = 1 }
                        BigButton("Listas", Modifier.weight(1f)) { panel = 2 }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigText(t: String) {
    Text(t, color = Color(0xFFBBBBBB), fontSize = 22.sp, modifier = Modifier.padding(24.dp))
}

@Composable
private fun BigButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(84.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF222222)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF181818))
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp).height(64.dp),
        verticalArrangement = Arrangement.Center) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Color(0xFFAAAAAA), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
