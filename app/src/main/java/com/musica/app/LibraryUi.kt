package com.musica.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tema oscuro con acento naranja, estilo Poweramp. */
val PowerampScheme = darkColorScheme(
    primary = Color(0xFFFF9800), onPrimary = Color.Black,
    background = Color(0xFF0D0D0D), onBackground = Color.White,
    surface = Color(0xFF0D0D0D), onSurface = Color.White,
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainer = Color(0xFF1A1A1A), primaryContainer = Color(0xFF3A2A10), onPrimaryContainer = Color(0xFFFFCC80)
)

private fun keyOf(s: Song, tab: Int): String = when (tab) {
    1 -> if (s.artist.contains("unknown", true) || s.artist.isBlank()) "Desconocido" else s.artist
    2 -> s.album.ifBlank { "Sin álbum" }
    else -> s.genre.ifBlank { "Sin género" }
}

private fun mmss(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

@Composable
fun Art(s: Song, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s.id) { value = Repo.thumb(ctx, s) }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

class SongActions(
    val favs: Set<Long>, val playNext: (Song) -> Unit, val addQueue: (Song) -> Unit,
    val toggleFav: (Song) -> Unit, val addToList: (Song) -> Unit)

@Composable
fun LibraryScreen(songs: List<Song>, playlists: Map<String, List<Long>>, query: String, actions: SongActions,
                  onNewList: (String) -> Unit, onDeleteList: (String) -> Unit, modifier: Modifier,
                  onPlay: (List<Song>, Int) -> Unit) {
    val tabs = listOf("CANCIONES", "ARTISTAS", "ÁLBUMES", "GÉNEROS", "FAVORITAS", "LISTAS")
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var newList by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    BackHandler(open != null) { open = null }
    if (newList) AlertDialog(onDismissRequest = { newList = false }, title = { Text("Nueva lista") },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
        confirmButton = { TextButton({ if (newName.isNotBlank()) onNewList(newName.trim()); newName = ""; newList = false }) { Text("Crear") } },
        dismissButton = { TextButton({ newList = false }) { Text("Cancelar") } })
    Column(modifier) {
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            SongList(songs.filter { "${it.title} ${it.artist} ${it.album} ${it.genre}".lowercase().contains(q) }, actions, onPlay)
            return@Column
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; open = null },
                    text = { Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val o = open
        if (o != null) {
            val list = if (o.first == 5) (playlists[o.second] ?: emptyList()).mapNotNull { id -> songs.firstOrNull { it.id == id } }
                       else songs.filter { keyOf(it, o.first) == o.second }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ open = null }) { Icon(Icons.Default.ArrowBack, "Volver") }
                Text(o.second, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                if (o.first == 5) IconButton({ onDeleteList(o.second); open = null }) { Icon(Icons.Default.Delete, "Borrar lista") }
            }
            SongList(list, actions, onPlay)
        } else when (tab) {
            0 -> SongList(songs, actions, onPlay)
            4 -> SongList(songs.filter { it.id in actions.favs }, actions, onPlay)
            5 -> Column {
                TextButton({ newList = true }) { Text("+ NUEVA LISTA") }
                LazyColumn {
                    itemsIndexed(playlists.entries.toList()) { _, e ->
                        ListItem(
                            headlineContent = { Text(e.key, maxLines = 1) },
                            supportingContent = { Text("${e.value.size} canciones") },
                            leadingContent = { Icon(Icons.Default.QueueMusic, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { open = 5 to e.key })
                    }
                }
            }
            else -> {
                val groups = songs.groupBy { keyOf(it, tab) }.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.toList()
                LazyColumn {
                    itemsIndexed(groups) { _, g ->
                        ListItem(
                            headlineContent = { Text(g.key, maxLines = 1) },
                            supportingContent = { Text("${g.value.size} canciones") },
                            leadingContent = { Art(g.value.first(), 52.dp) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { open = tab to g.key })
                    }
                }
            }
        }
    }
}

@Composable
fun SongList(list: List<Song>, actions: SongActions, onPlay: (List<Song>, Int) -> Unit) {
    LazyColumn {
        itemsIndexed(list) { i, s ->
            var menu by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(s.title, maxLines = 1) },
                supportingContent = { Text("${keyOf(s, 1)} · ${keyOf(s, 2)}", maxLines = 1) },
                leadingContent = { Art(s, 52.dp) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.id in actions.favs) Icon(Icons.Default.Favorite, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(" " + mmss(s.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Reproducir siguiente") }, { actions.playNext(s); menu = false })
                                DropdownMenuItem({ Text("Añadir a la cola") }, { actions.addQueue(s); menu = false })
                                DropdownMenuItem({ Text(if (s.id in actions.favs) "Quitar de favoritas" else "Añadir a favoritas") },
                                    { actions.toggleFav(s); menu = false })
                                DropdownMenuItem({ Text("Añadir a lista") }, { actions.addToList(s); menu = false })
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onPlay(list, i) })
        }
    }
}
