package com.musica.app

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Visualizador basado en android.media.audiofx.Visualizer.
 * Solo corre mientras la pantalla del reproductor está visible, la app en primer plano y la música sonando,
 * a unos 20 cuadros por segundo con 512 muestras, para gastar poca batería.
 */
object Viz {
    const val NB = 32
    var bars by mutableStateOf(FloatArray(NB))
    var wave by mutableStateOf(FloatArray(96))
    var bass by mutableFloatStateOf(0f)
    private var vis: Visualizer? = null

    fun start(sessionId: Int): Boolean {
        stop()
        if (sessionId == 0) return false
        return try {
            val v = Visualizer(sessionId)
            val range = Visualizer.getCaptureSizeRange()
            v.setCaptureSize(512.coerceIn(range[0], range[1]))
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform != null) updateWave(waveform)
                }
                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) updateFft(fft)
                }
            }, minOf(Visualizer.getMaxCaptureRate(), 20000), true, true)
            v.setEnabled(true)
            vis = v
            true
        } catch (t: Throwable) { vis = null; false }
    }

    fun stop() {
        try { vis?.setEnabled(false) } catch (t: Throwable) { }
        try { vis?.release() } catch (t: Throwable) { }
        vis = null
        bars = FloatArray(NB); wave = FloatArray(96); bass = 0f
    }

    private fun updateWave(w: ByteArray) {
        val out = FloatArray(96)
        val step = w.size / 96.0
        for (i in 0 until 96) out[i] = (((w[(i * step).toInt().coerceIn(0, w.size - 1)].toInt() and 0xFF) - 128) / 128f)
        wave = out
    }

    private fun updateFft(f: ByteArray) {
        val half = f.size / 2
        val old = bars
        val out = FloatArray(NB)
        var lo = 1
        val maxK = half - 1
        for (i in 0 until NB) {
            val hiF = Math.exp(Math.log(maxK.toDouble()) * (i + 1) / NB)
            val hi = maxOf(lo + 1, hiF.toInt() + 1).coerceAtMost(maxK + 1)
            var sum = 0f; var n = 0
            for (k in lo until hi) {
                val re = f[2 * k].toFloat(); val im = f[2 * k + 1].toFloat()
                sum += Math.hypot(re.toDouble(), im.toDouble()).toFloat(); n++
            }
            val mag = if (n == 0) 0f else sum / n
            val db = 20f * Math.log10((mag + 1f).toDouble()).toFloat()
            val x = ((db - 6f) / 39f).coerceIn(0f, 1f)
            out[i] = maxOf(x, old[i] * 0.82f)
            lo = hi
        }
        bars = out
        bass = (out[0] + out[1] + out[2]) / 3f
    }
}

/** style: 1 = barras de espectro, 2 = onda. Solo se redibuja (no se recompone) en cada cuadro. */
@Composable
fun VisualizerView(style: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (style == 1) {
            val b = Viz.bars
            val gap = 4.dp.toPx()
            val bw = (size.width - gap * (b.size - 1)) / b.size
            for (i in b.indices) {
                val h = (b[i] * size.height).coerceAtLeast(3.dp.toPx())
                drawRoundRect(color.copy(alpha = 0.55f + 0.45f * b[i]),
                    Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(bw / 2, bw / 2))
            }
        } else {
            val w = Viz.wave
            val path = Path()
            for (i in w.indices) {
                val x = size.width * i / (w.size - 1)
                val y = size.height / 2f - w[i] * size.height * 0.9f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
