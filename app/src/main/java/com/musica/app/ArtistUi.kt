package com.musica.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun fmtMs(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

/** Biografía del artista, un dato curioso, su discografía y lo que tienes de él en la biblioteca. */
@Composable
fun ArtistScreen(artist: String, songs: List<Song>, onPlay: (List<Song>, Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val mine = remember(songs, artist) { songs.filter { it.artist.equals(artist, true) } }
    var bio by remember(artist) { mutableStateOf<ArtistBio?>(null) }
    var disco by remember(artist) { mutableStateOf<List<DiscoItem>>(emptyList()) }
    var loaded by remember(artist) { mutableStateOf(false) }
    LaunchedEffect(artist) {
        bio = ArtistInfo.bio(ctx, artist)
        disco = ArtistInfo.discography(artist)
        loaded = true
    }
    val albums = remember(mine) { mine.map { it.album }.filter { it.isNotBlank() }.distinct() }
    val localFact = remember(mine, Stats.version) {
        val evs = Stats.all(ctx).filter { it.artist.equals(artist, true) }
        val plays = evs.count { it.counted }
        if (plays > 0) "Lo has escuchado $plays ${if (plays == 1) "vez" else "veces"} (${Stats.fmtDur(evs.sumOf { it.ms })} en total)."
        else mine.maxByOrNull { it.durationMs }?.let { "Tu canción más larga de $artist es «${it.title}» (${fmtMs(it.durationMs)})." }
            ?: "Aún no has escuchado a $artist con esta app."
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mine.isNotEmpty()) CoverTile(mine.first(), Modifier.size(200.dp), 100.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(artist, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    val total = mine.sumOf { it.durationMs }
                    Text("${mine.size} canciones · ${albums.size} álbumes · ${Stats.fmtDur(total)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ if (mine.isNotEmpty()) onPlay(mine, 0) }, enabled = mine.isNotEmpty()) { Text("Reproducir") }
                        OutlinedButton({ if (mine.isNotEmpty()) onPlay(mine.shuffled(), 0) }, enabled = mine.isNotEmpty()) { Text("Aleatorio") }
                    }
                }
            }
            item { SectionHeader("Biografía") }
            item {
                val b = bio
                Column(Modifier.padding(horizontal = 20.dp)) {
                    when {
                        !loaded -> Text("Buscando información…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        b != null -> {
                            Text(b.extract, fontSize = 15.sp, lineHeight = 22.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton({
                                    try {
                                        if (b.url.startsWith("https://"))
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (e: Exception) { }
                                }, contentPadding = PaddingValues(0.dp)) { Text("Leer más en Wikipedia") }
                            }
                            Text("Fuente: Wikipedia (CC BY-SA)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Text("No se encontró una biografía (sin conexión o artista poco conocido).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionHeader("Dato curioso") }
            item {
                Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp), 0.08f).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bio?.curiosity?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp) }
                    Text(localFact, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (disco.isNotEmpty()) {
                item { SectionHeader("Discografía") }
                itemsIndexed(disco) { _, d ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(d.name, Modifier.weight(1f), maxLines = 1)
                        Text(d.year, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Text("Datos de iTunes", Modifier.padding(horizontal = 20.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { SectionHeader("En tu biblioteca") }
            itemsIndexed(mine) { i, s ->
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1) },
                    supportingContent = { Text(s.album.ifBlank { "Sin álbum" }, maxLines = 1) },
                    leadingContent = { Art(s, 48.dp, 10.dp) },
                    trailingContent = { Text(fmtMs(s.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPlay(mine, i) })
            }
        }
    }
}

@Composable
private fun SectionHeader(t: String) {
    Text(t, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}
