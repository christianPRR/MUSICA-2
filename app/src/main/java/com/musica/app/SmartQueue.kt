package com.musica.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import kotlin.random.Random

/** Sugerencias y «canciones similares» calculadas solo con la biblioteca local y el historial. */
object Smart {
    private fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
    private fun known(x: String) = x.isNotBlank() && !x.contains("unknown", true) && x != "Desconocido"

    fun similar(songs: List<Song>, seed: Song, exclude: Set<Long>, history: List<PlayEv>, limit: Int): List<Song> {
        val artistPlays = history.filter { it.counted }.groupingBy { norm(it.artist) }.eachCount()
        val sa = norm(seed.artist)
        return songs.asSequence()
            .filter { it.id != seed.id && it.id !in exclude }
            .map { s ->
                var sc = 0f
                if (known(s.artist) && norm(s.artist) == sa) sc += 4f
                if (s.album.isNotBlank() && s.album.equals(seed.album, true)) sc += 3f
                if (s.genre.isNotBlank() && s.genre.equals(seed.genre, true)) sc += 2f
                sc += minOf(artistPlays[norm(s.artist)] ?: 0, 20) * 0.05f
                sc += Random.nextFloat() * 0.8f
                s to sc
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Favoritas que llevan más tiempo sin sonar. */
    fun rediscover(songs: List<Song>, favs: Set<Long>, history: List<PlayEv>, limit: Int): List<Song> {
        val last = HashMap<Long, Long>()
        for (e in history) if (e.counted) last[e.id] = maxOf(last[e.id] ?: 0L, e.ts)
        return songs.filter { it.id in favs }.sortedBy { last[it.id] ?: 0L }.take(limit)
    }

    /** Canciones distintas reproducidas recientemente, la más reciente primero. */
    fun recent(history: List<PlayEv>, limit: Int): List<PlayEv> {
        val seen = HashSet<Long>()
        val out = ArrayList<PlayEv>()
        for (e in history.asReversed()) {
            if (!e.counted || !seen.add(e.id)) continue
            out.add(e); if (out.size >= limit) break
        }
        return out
    }

    fun ago(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60000
        return when {
            m < 1 -> "ahora"
            m < 60 -> "hace $m min"
            m < 1440 -> "hace ${m / 60} h"
            else -> "hace ${m / 1440} d"
        }
    }
}

private fun upcoming(c: MediaController): List<MediaItem> {
    val s = c.currentMediaItemIndex + 1
    return (s until c.mediaItemCount).map { c.getMediaItemAt(it) }
}

private fun replaceUpcoming(c: MediaController, items: List<MediaItem>) {
    val s = c.currentMediaItemIndex + 1
    if (s < c.mediaItemCount) c.removeMediaItems(s, c.mediaItemCount)
    if (items.isNotEmpty()) c.addMediaItems(items)
}

private fun playNow(c: MediaController, s: Song) {
    if (c.mediaItemCount == 0) { c.setMediaItem(s.item); c.prepare(); c.play(); return }
    c.addMediaItem(c.currentMediaItemIndex + 1, s.item)
    c.seekToNextMediaItem(); c.play()
}

private fun enqueue(c: MediaController, s: Song) {
    if (c.mediaItemCount == 0) { c.setMediaItem(s.item); c.prepare() } else c.addMediaItem(s.item)
}

@Composable
fun QueueScreen(c: MediaController?, songs: List<Song>, favs: Set<Long>,
                onSaveList: (String, List<Long>) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    var cur by remember { mutableIntStateOf(c?.currentMediaItemIndex ?: 0) }
    var tab by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    DisposableEffect(c) {
        val l = object : Player.Listener {
            override fun onTimelineChanged(t: Timeline, r: Int) { cur = c?.currentMediaItemIndex ?: 0; ver++ }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { cur = c?.currentMediaItemIndex ?: 0; ver++ }
        }
        c?.addListener(l)
        onDispose { c?.removeListener(l) }
    }
    val v = ver
    val n = c?.mediaItemCount ?: 0
    val byId = remember(songs) { songs.associateBy { it.id } }
    val history = remember(Stats.version) { Stats.all(ctx) }
    val currentSong = c?.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
    val inQueue = remember(v, n) { (0 until n).mapNotNull { c?.getMediaItemAt(it)?.mediaId?.toLongOrNull() }.toSet() }

    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("Guardar cola como lista") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nombre de la lista") }) },
        confirmButton = { TextButton({
            if (name.isNotBlank() && c != null) {
                onSaveList(name.trim(), (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() })
            }
            name = ""; saving = false
        }) { Text("Guardar") } },
        dismissButton = { TextButton({ saving = false }) { Text("Cancelar") } })

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                Text("Cola inteligente", style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Cola ($n)", "Historial", "Para ti").forEachIndexed { i, t ->
                    FilterChip(tab == i, { tab = i }, { Text(t) })
                }
            }
            when (tab) {
                0 -> {
                    if (c == null || n == 0) Text("La cola está vacía", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip({ replaceUpcoming(c, upcoming(c).shuffled()) }, { Text("Mezclar siguientes") })
                            AssistChip({ replaceUpcoming(c, upcoming(c).sortedBy { it.mediaMetadata.title?.toString()?.lowercase() ?: "" }) }, { Text("Ordenar A–Z") })
                            AssistChip({
                                val seen = mutableSetOf(c.currentMediaItem?.mediaId)
                                replaceUpcoming(c, upcoming(c).filter { seen.add(it.mediaId) })
                            }, { Text("Quitar repetidas") })
                            AssistChip({ replaceUpcoming(c, emptyList()) }, { Text("Limpiar siguientes") })
                            AssistChip({ saving = true }, { Text("Guardar como lista") })
                            AssistChip({
                                currentSong?.let { s ->
                                    val sim = Smart.similar(songs, s, emptySet(), history, 30)
                                    replaceUpcoming(c, sim.map { it.item })
                                }
                            }, { Text("Reproducir similares") })
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            itemsIndexed((0 until n).map { c.getMediaItemAt(it) }) { i, item ->
                                Row(Modifier.fillMaxWidth().clickable { c.seekTo(i, 0L); c.play() }.padding(start = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.mediaMetadata.title?.toString() ?: "", maxLines = 1,
                                            color = if (i == cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal)
                                        Text((if (i == cur) "Reproduciendo · " else if (i == cur + 1) "A continuación · " else "") +
                                            (item.mediaMetadata.artist?.toString() ?: ""), maxLines = 1, fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton({ if (i > 0) c.moveMediaItem(i, i - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
                                    IconButton({ if (i < n - 1) c.moveMediaItem(i, i + 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Bajar") }
                                    IconButton({ c.removeMediaItem(i) }) { Icon(Icons.Default.Close, "Quitar") }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val recent = remember(history) { Smart.recent(history, 60) }
                    if (recent.isEmpty()) Text("Aún no hay historial. Aparecerá aquí lo que vayas escuchando.",
                        Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(recent) { _, e ->
                            val s = byId[e.id]
                            Row(Modifier.fillMaxWidth().clickable(enabled = s != null && c != null) { playNow(c!!, s!!) }
                                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (s != null) { Art(s, 48.dp, 10.dp); androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                    Text(e.artist, maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(Smart.ago(e.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (s != null && c != null) IconButton({ enqueue(c, s) }) {
                                    Icon(Icons.Default.KeyboardArrowDown, "Añadir a la cola")
                                }
                            }
                        }
                    }
                }
                else -> {
                    val seed = currentSong
                    val sim = remember(seed?.id, inQueue, history) {
                        if (seed == null) emptyList() else Smart.similar(songs, seed, inQueue, history, 20)
                    }
                    val redisc = remember(favs, history) { Smart.rediscover(songs, favs, history, 10) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (seed != null && sim.isNotEmpty()) {
                            item {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Parecidas a «${seed.title}»", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    TextButton({ c?.let { cc -> replaceUpcoming(cc, sim.map { it.item }) } }) { Text("Reproducir") }
                                }
                            }
                            itemsIndexed(sim) { _, s -> SuggestionRow(s, c) }
                        } else item {
                            Text("Reproduce una canción para ver sugerencias de tu biblioteca.", Modifier.padding(20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (redisc.isNotEmpty()) {
                            item { Text("Favoritas para redescubrir", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                            itemsIndexed(redisc) { _, s -> SuggestionRow(s, c) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(s: Song, c: MediaController?) {
    Row(Modifier.fillMaxWidth().clickable(enabled = c != null) { playNow(c!!, s) }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(s, 48.dp, 10.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
            Text(s.artist, maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({ c?.let { enqueue(it, s) } }) { Text("+ Cola") }
    }
}
