package com.musica.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

class KWord(val t: Long, val text: String)
class KLine(val t: Long, val text: String, val words: List<KWord>)
class Lyrics(val lines: List<KLine>, val synced: Boolean)

/** Lector de letras LRC. Entiende también el LRC «mejorado» con tiempos por palabra: [00:12.00] <00:12.00>Hola <00:12.50>mundo */
object Lrc {
    private val lineTag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
    private val wordTag = Regex("""<(\d+):(\d+)(?:[.:](\d+))?>""")

    private fun toMs(m: String, s: String, f: String): Long {
        val frac = if (f.isEmpty()) 0L else f.padEnd(3, '0').take(3).toLong()
        return (m.toLong() * 60 + s.toLong()) * 1000 + frac
    }

    fun parse(text: String): Lyrics {
        val out = mutableListOf<KLine>()
        for (line in text.lines()) {
            val tags = lineTag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val raw = line.substring(tags.last().range.last + 1)
            val wm = wordTag.findAll(raw).toList()
            val words = mutableListOf<KWord>()
            for ((i, w) in wm.withIndex()) {
                val end = if (i + 1 < wm.size) wm[i + 1].range.first else raw.length
                val t = raw.substring(w.range.last + 1, end)
                if (t.isBlank()) continue
                words.add(KWord(toMs(w.groupValues[1], w.groupValues[2], w.groupValues[3]), t))
            }
            val txt = wordTag.replace(raw, "").trim()
            for (m in tags) out.add(KLine(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]), txt, words))
        }
        return if (out.isNotEmpty()) Lyrics(out.sortedBy { it.t }, true)
        else Lyrics(text.lines().map { KLine(0L, it.trim(), emptyList()) }, false)
    }
}

/**
 * Letra estilo karaoke: la línea actual se resalta y se centra sola, las demás se atenúan.
 * Si la letra trae tiempos por palabra, cada palabra se ilumina en su momento.
 * Al tocar una línea sincronizada, la canción salta a ese punto (adelante o atrás).
 */
@Composable
fun KaraokeView(l: Lyrics?, status: String, c: MediaController?, playing: Boolean, size: Int, modifier: Modifier) {
    if (l == null || l.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(status, color = Color.White.copy(alpha = 0.6f)) }
        return
    }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c, playing) {
        while (true) { pos = c?.currentPosition ?: 0L; delay(if (playing) 45L else 300L) }
    }
    val idx by remember(l) {
        derivedStateOf { if (l.synced) l.lines.indexOfLast { it.t <= pos } else -1 }
    }
    val state = rememberLazyListState()
    LaunchedEffect(idx) {
        if (idx >= 0) try {
            state.animateScrollToItem(idx, -(state.layoutInfo.viewportSize.height / 3))
        } catch (e: Exception) { }
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(top = 24.dp, bottom = 260.dp)) {
        itemsIndexed(l.lines) { i, line ->
            val active = l.synced && i == idx
            val dist = if (l.synced) Math.abs(i - idx) else 0
            val target = when {
                !l.synced -> 0.9f
                active -> 1f
                else -> maxOf(0.18f, 0.5f - 0.08f * minOf(dist, 4))
            }
            val a by animateFloatAsState(target, tween(300), label = "lyricAlpha")
            val sc by animateFloatAsState(if (active || !l.synced) 1f else 0.92f, tween(300), label = "lyricScale")
            Box(Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, 0.5f); alpha = a }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = l.synced) { c?.seekTo(line.t) }
                .padding(vertical = 8.dp)) {
                val fs = (if (l.synced) size else size - 6).sp
                if (active && line.words.isNotEmpty()) {
                    WordLine(line, { pos }, fs.value)
                } else {
                    Text(if (line.text.isBlank()) "♪" else line.text, color = Color.White, fontSize = fs,
                        fontWeight = FontWeight.Bold, lineHeight = (fs.value * 1.25f).sp)
                }
            }
        }
    }
}

@Composable
private fun WordLine(line: KLine, pos: () -> Long, fs: Float) {
    val p = pos()
    val dim = Color.White.copy(alpha = 0.35f)
    val text = buildAnnotatedString {
        for ((i, w) in line.words.withIndex()) {
            val end = line.words.getOrNull(i + 1)?.t ?: (w.t + 600L)
            val prog = ((p - w.t).toFloat() / (end - w.t).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = lerp(dim, Color.White, prog))) { append(w.text) }
        }
    }
    Text(text, fontSize = fs.sp, fontWeight = FontWeight.Bold, lineHeight = (fs * 1.25f).sp)
}
