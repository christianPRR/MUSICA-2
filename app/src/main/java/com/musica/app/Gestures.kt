package com.musica.app

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gestos sobre la portada:
 *  - deslizar a los lados: canción siguiente o anterior (la portada acompaña al dedo)
 *  - deslizar arriba o abajo: volumen del sistema
 *  - doble toque a la derecha o izquierda: adelantar o retroceder 10 s
 * Cada gesto muestra una burbuja de confirmación.
 */
@Composable
fun GestureBox(c: MediaController?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    var hud by remember { mutableStateOf<String?>(null) }
    var lastHud by remember { mutableStateOf("") }
    var hudTick by remember { mutableIntStateOf(0) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    LaunchedEffect(hudTick) { if (hud != null) { delay(900); hud = null } }
    fun show(t: String) { lastHud = t; hud = t; hudTick++ }

    Box(modifier
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(c) {
            detectTapGestures(onDoubleTap = { o ->
                val cc = c
                if (cc != null) {
                    if (o.x > size.width / 2) {
                        val d = cc.duration; val t = cc.currentPosition + 10000L
                        cc.seekTo(if (d > 0) minOf(t, d) else t)
                        show("+10 s")
                    } else {
                        cc.seekTo((cc.currentPosition - 10000L).coerceAtLeast(0L))
                        show("−10 s")
                    }
                }
            })
        }
        .pointerInput(c) {
            var mode = 0
            var ax = 0f; var ay = 0f
            var vol = 0f
            detectDragGestures(
                onDragStart = { mode = 0; ax = 0f; ay = 0f; vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() },
                onDragEnd = {
                    if (mode == 1) {
                        val dx = off.value
                        if (abs(dx) > widthPx * 0.22f) {
                            val dir = if (dx < 0) -1f else 1f
                            scope.launch {
                                off.animateTo(dir * widthPx, tween(150))
                                if (dir < 0) { c?.seekToNextMediaItem(); show("Siguiente") }
                                else { c?.seekToPreviousMediaItem(); show("Anterior") }
                                off.snapTo(-dir * widthPx * 0.45f)
                                off.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                            }
                        } else scope.launch { off.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy)) }
                    }
                    mode = 0
                },
                onDragCancel = { mode = 0; scope.launch { off.animateTo(0f) } },
                onDrag = { change, drag ->
                    change.consume()
                    if (mode == 0) {
                        ax += drag.x; ay += drag.y
                        if (abs(ax) > 12f || abs(ay) > 12f) mode = if (abs(ax) > abs(ay)) 1 else 2
                    }
                    if (mode == 1) {
                        scope.launch { off.snapTo(off.value + drag.x) }
                    } else if (mode == 2) {
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        vol = (vol - drag.y / size.height * max * 1.6f).coerceIn(0f, max.toFloat())
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.roundToInt(), 0)
                        show("Volumen ${(vol / max * 100).roundToInt()}%")
                    }
                }
            )
        }
    ) {
        Box(Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = off.value
                rotationZ = off.value / widthPx * 8f
                alpha = 1f - (abs(off.value) / widthPx) * 0.6f
            }, content = content)
        AnimatedVisibility(hud != null, Modifier.align(Alignment.Center),
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.9f), exit = fadeOut(tween(250))) {
            Box(Modifier.glass(RoundedCornerShape(20.dp), 0.18f).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(lastHud, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Deslizar a los lados cambia de canción; deslizar hacia arriba ejecuta [onUp] (abrir el reproductor). */
fun Modifier.swipeSongs(c: MediaController?, onUp: () -> Unit = {}): Modifier = this.pointerInput(c) {
    var dx = 0f; var dy = 0f
    detectDragGestures(
        onDragStart = { dx = 0f; dy = 0f },
        onDrag = { change, d -> change.consume(); dx += d.x; dy += d.y },
        onDragEnd = {
            if (abs(dx) > abs(dy) && abs(dx) > 90f) {
                if (dx < 0) c?.seekToNextMediaItem() else c?.seekToPreviousMediaItem()
            } else if (dy < -70f) onUp()
        }
    )
}
