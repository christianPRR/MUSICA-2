package com.musica.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private fun keyOf(s: Song, tab: Int): String = when (tab) {
    1 -> if (s.artist.contains("unknown", true) || s.artist.isBlank()) "Desconocido" else s.artist
    2 -> s.album.ifBlank { "Sin álbum" }
    else -> s.genre.ifBlank { "Sin género" }
}

private fun mmss(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

/** Portada con esquinas redondeadas que ocupa el tamaño que se le dé. */
@Composable
fun CoverTile(s: Song, modifier: Modifier, corner: Dp = 14.dp, big: Boolean = true) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s.id, big) { value = if (big) Repo.bigThumb(ctx, s) else Repo.thumb(ctx, s) }
    Box(modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Art(s: Song, size: Dp, corner: Dp = 8.dp) = CoverTile(s, Modifier.size(size), corner, big = size > 72.dp)

class SongActions(
    val favs: Set<Long>, val playNext: (Song) -> Unit, val addQueue: (Song) -> Unit,
    val toggleFav: (Song) -> Unit, val addToList: (Song) -> Unit, val onArtist: (Song) -> Unit)

@Composable
fun LibraryScreen(songs: List<Song>, playlists: Map<String, List<Long>>, query: String, actions: SongActions,
                  onNewList: (String) -> Unit, onDeleteList: (String) -> Unit, onExportList: (String) -> Unit,
                  onArtist: (String) -> Unit, modifier: Modifier, onPlay: (List<Song>, Int) -> Unit) {
    val tabs = listOf("Canciones", "Artistas", "Álbumes", "Géneros", "Favoritas", "Listas")
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var newList by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val bottom = LocalBottomPad.current
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
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary, divider = {}) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; open = null },
                    text = { Text(t, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val o = open
        if (o != null) {
            val list = if (o.first == 5) (playlists[o.second] ?: emptyList()).mapNotNull { id -> songs.firstOrNull { it.id == id } }
                       else songs.filter { keyOf(it, o.first) == o.second }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ open = null }) { Icon(Icons.Default.ArrowBack, "Volver") }
                Text(o.second, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
                if (o.first == 1) IconButton({ onArtist(o.second) }) { Icon(Icons.Default.Info, "Sobre el artista") }
                if (o.first == 5) {
                    IconButton({ onExportList(o.second) }) { Icon(Icons.Default.Share, "Exportar lista (M3U)") }
                    IconButton({ onDeleteList(o.second); open = null }) { Icon(Icons.Default.Delete, "Borrar lista") }
                }
            }
            SongList(list, actions, onPlay)
        } else when (tab) {
            0 -> SongList(songs, actions, onPlay)
            4 -> SongList(songs.filter { it.id in actions.favs }, actions, onPlay)
            5 -> Column {
                TextButton({ newList = true }) { Text("+ Nueva lista") }
                LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
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
            2 -> {
                val groups = remember(songs) { songs.groupBy { keyOf(it, 2) }.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.toList() }
                LazyVerticalGrid(GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(groups) { g ->
                        Column(Modifier.clickable { open = 2 to g.key }) {
                            CoverTile(g.value.first(), Modifier.fillMaxWidth().aspectRatio(1f), 18.dp)
                            Text(g.key, Modifier.padding(top = 8.dp), maxLines = 1, fontWeight = FontWeight.SemiBold)
                            Text(keyOf(g.value.first(), 1), maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> {
                val groups = remember(songs, tab) { songs.groupBy { keyOf(it, tab) }.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.toList() }
                LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                    itemsIndexed(groups) { _, g ->
                        ListItem(
                            headlineContent = { Text(g.key, maxLines = 1) },
                            supportingContent = { Text("${g.value.size} canciones") },
                            leadingContent = { CoverTile(g.value.first(), Modifier.size(52.dp), if (tab == 1) 26.dp else 10.dp, big = false) },
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
    LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomPad.current)) {
        itemsIndexed(list) { i, s ->
            var menu by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(s.title, maxLines = 1) },
                supportingContent = { Text("${keyOf(s, 1)} · ${keyOf(s, 2)}", maxLines = 1) },
                leadingContent = { Art(s, 52.dp, 10.dp) },
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
                                DropdownMenuItem({ Text("Sobre el artista") }, { actions.onArtist(s); menu = false })
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onPlay(list, i) })
        }
    }
}
