package com.musica.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/** Pantalla de inicio: saludo, «continúa escuchando», filas de portadas y tu resumen de la semana. */
@Composable
fun HomeScreen(songs: List<Song>, favs: Set<Long>, onPlay: (List<Song>, Int) -> Unit,
               onArtist: (String) -> Unit, onStats: () -> Unit, onCar: () -> Unit) {
    val ctx = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val cell = if (wide) 190.dp else 148.dp
    val byId = remember(songs) { songs.associateBy { it.id } }
    val all = remember(Stats.version) { Stats.all(ctx) }
    val recent = remember(all, byId) { Smart.recent(all, 14).mapNotNull { byId[it.id] } }
    val hero = recent.firstOrNull() ?: songs.firstOrNull()
    val day = remember { Calendar.getInstance().get(Calendar.DAY_OF_YEAR).toLong() }
    val mix = remember(songs) { songs.shuffled(kotlin.random.Random(day * 31 + songs.size)).take(12) }
    val favList = remember(songs, favs) { songs.filter { it.id in favs }.take(14) }
    val albums = remember(songs) {
        songs.filter { it.album.isNotBlank() }.groupBy { it.album }.values.map { it.first() }
            .shuffled(kotlin.random.Random(day + 7)).take(12)
    }
    val week = remember(all) { Stats.inPeriod(all, 0) }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = if (hour < 12) "Buenos días" else if (hour < 19) "Buenas tardes" else "Buenas noches"
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = LocalBottomPad.current + 16.dp)) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
                    Text(greeting, style = MaterialTheme.typography.headlineLarge)
                    Text("${songs.size} canciones en tu biblioteca", color = muted)
                }
            }
            if (hero != null) item {
                Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.30f), MaterialTheme.colorScheme.surfaceContainer)))
                    .clickable { onPlay(songs, songs.indexOf(hero).coerceAtLeast(0)) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CoverTile(hero, Modifier.size(if (wide) 170.dp else 128.dp), 22.dp)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(if (recent.isNotEmpty()) "Continúa escuchando" else "Para empezar", fontSize = 12.sp, color = accent,
                            fontWeight = FontWeight.SemiBold)
                        Text(hero.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        Text(hero.artist, color = muted, maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        Button({ onPlay(songs, songs.indexOf(hero).coerceAtLeast(0)) }) { Text("Reproducir") }
                    }
                }
            }
            if (recent.size > 1) item { CoverRow("Reproducido recientemente", recent.drop(1), cell) { i -> onPlay(recent.drop(1), i) } }
            if (mix.isNotEmpty()) item { CoverRow("Mezcla de hoy", mix, cell) { i -> onPlay(mix, i) } }
            if (favList.isNotEmpty()) item { CoverRow("Tus favoritas", favList, cell) { i -> onPlay(favList, i) } }
            if (albums.isNotEmpty()) item {
                CoverRow("Álbumes para descubrir", albums, cell) { i ->
                    val a = albums[i]; onPlay(songs.filter { it.album == a.album }, 0)
                }
            }
            item {
                Column(Modifier.padding(16.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), 0.08f).padding(18.dp)) {
                    Text("Tu semana", style = MaterialTheme.typography.titleMedium, color = muted)
                    Spacer(Modifier.height(6.dp))
                    for (l in Stats.summary(week, "esta semana").take(3)) Text(l, Modifier.padding(vertical = 2.dp), fontSize = 15.sp)
                    Row {
                        TextButton(onStats) { Text("Ver estadísticas") }
                        TextButton(onCar) { Text("Modo auto") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverRow(title: String, list: List<Song>, cell: Dp, onClick: (Int) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(list.size) { i ->
                val s = list[i]
                Column(Modifier.width(cell).clickable { onClick(i) }) {
                    CoverTile(s, Modifier.size(cell), 20.dp)
                    Text(s.title, Modifier.padding(top = 8.dp), maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text(s.artist, maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
