package com.musica.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController

@Composable
fun QueueScreen(c: MediaController?, onClose: () -> Unit) {
    var ver by remember { mutableIntStateOf(0) }
    var cur by remember { mutableIntStateOf(c?.currentMediaItemIndex ?: 0) }
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                Text("Cola de reproducción ($n)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (n == 0 || c == null) Text("La cola está vacía", Modifier.padding(20.dp))
            else LazyColumn {
                itemsIndexed((0 until n).map { c.getMediaItemAt(it) }) { i, item ->
                    Row(Modifier.fillMaxWidth().clickable { c.seekTo(i, 0L); c.play() }.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.mediaMetadata.title?.toString() ?: "", maxLines = 1,
                                color = if (i == cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal)
                            Text(item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, fontSize = 13.sp,
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
}
