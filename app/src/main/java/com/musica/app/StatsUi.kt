package com.musica.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estadísticas de escucha: resumen, tiempo total, últimos 7 días, rankings e historial. */
@Composable
fun StatsScreen(songs: List<Song>, onPlay: (List<Song>, Int) -> Unit, onArtist: (String) -> Unit) {
    val ctx = LocalContext.current
    var period by remember { mutableIntStateOf(0) }
    val version = Stats.version
    val all = remember(version) { Stats.all(ctx) }
    val evs = remember(all, period) { Stats.inPeriod(all, period) }
    val label = listOf("esta semana", "este mes", "en total")[period]
    val byId = remember(songs) { songs.associateBy { it.id } }
    val topSongs = remember(evs) { Stats.topSongs(evs).take(5) }
    val topArtists = remember(evs) { Stats.topArtists(evs).take(5) }
    val topAlbums = remember(evs) { Stats.topAlbums(evs).take(5) }
    val days = remember(all) { Stats.lastSevenDays(all) }
    val history = remember(all) { Smart.recent(all, 40) }
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomPad.current + 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Semana", "Mes", "Todo").forEachIndexed { i, t -> FilterChip(period == i, { period = i }, { Text(t) }) }
            }
        }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), 0.08f).padding(20.dp)) {
                Text("Tu resumen", style = MaterialTheme.typography.titleMedium, color = muted)
                Text(Stats.fmtDur(Stats.totalMs(evs)), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = accent)
                Text("${Stats.playCount(evs)} reproducciones $label", color = muted)
                Spacer(Modifier.height(12.dp))
                for (line in Stats.summary(evs, label)) Text(line, Modifier.padding(vertical = 2.dp), fontSize = 15.sp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Últimos 7 días", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val maxV = (days.maxOrNull() ?: 0L).coerceAtLeast(1L)
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    val gap = 10.dp.toPx()
                    val bw = (size.width - gap * 6) / 7
                    for (i in 0 until 7) {
                        val h = (days[i].toFloat() / maxV * size.height).coerceAtLeast(4.dp.toPx())
                        drawRoundRect(if (i == 6) accent else accent.copy(alpha = 0.45f),
                            Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(8.dp.toPx(), 8.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val f = SimpleDateFormat("EEE", Locale("es"))
                    for (i in 0 until 7) {
                        val d = Date(System.currentTimeMillis() - (6 - i) * 86400000L)
                        Text(f.format(d).take(3), Modifier.weight(1f), fontSize = 11.sp, color = muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        item { Header("Canciones más reproducidas") }
        if (topSongs.isEmpty()) item { Empty() }
        itemsIndexed(topSongs) { i, r ->
            val s = r.songId?.let { byId[it] }
            RankRow(i + 1, r, s, { if (s != null) onPlay(listOf(s), 0) })
        }
        item { Header("Artistas más escuchados") }
        if (topArtists.isEmpty()) item { Empty() }
        itemsIndexed(topArtists) { i, r ->
            val s = r.songId?.let { byId[it] }
            RankRow(i + 1, r, s, { onArtist(r.key) }, circle = true)
        }
        item { Header("Álbumes más escuchados") }
        if (topAlbums.isEmpty()) item { Empty() }
        itemsIndexed(topAlbums) { i, r ->
            RankRow(i + 1, r, r.songId?.let { byId[it] }, { })
        }
        item { Header("Historial") }
        if (history.isEmpty()) item { Empty() }
        itemsIndexed(history) { _, e ->
            val s = byId[e.id]
            ListItem(
                headlineContent = { Text(e.title, maxLines = 1) },
                supportingContent = { Text(e.artist, maxLines = 1) },
                leadingContent = { if (s != null) Art(s, 44.dp, 10.dp) else Box(Modifier.size(44.dp)) },
                trailingContent = { Text(Smart.ago(e.ts), fontSize = 12.sp, color = muted) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = s != null) { if (s != null) onPlay(listOf(s), 0) })
        }
    }
}

@Composable
private fun Header(t: String) {
    Text(t, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Empty() {
    Text("Sin datos todavía.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RankRow(n: Int, r: Rank, s: Song?, onClick: () -> Unit, circle: Boolean = false) {
    ListItem(
        headlineContent = { Text(r.key, maxLines = 1) },
        supportingContent = { Text((if (r.sub.isNotBlank()) r.sub + " · " else "") + Stats.fmtDur(r.ms), maxLines = 1) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$n", Modifier.padding(end = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (s != null) CoverTile(s, Modifier.size(48.dp), if (circle) 24.dp else 10.dp, big = false) else Box(Modifier.size(48.dp))
            }
        },
        trailingContent = { Text("${r.plays}×", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick))
}
