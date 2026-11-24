package com.musica.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * Crossfade real: un segundo reproductor «fantasma» termina de tocar la canción que sale mientras
 * el reproductor principal ya empezó la siguiente. Los dos comparten la sesión de audio, así que el
 * ecualizador del modo Envolvente actúa sobre ambos.
 *
 * Con el crossfade apagado este objeto no hace nada: no se crea el segundo reproductor, las canciones
 * no se superponen y el reproductor principal sigue su reproducción sin pausas (gapless).
 */
@OptIn(UnstableApi::class)
class Crossfader(private val ctx: Context, private val main: ExoPlayer, private val factory: DefaultRenderersFactory) {
    private val h = Handler(Looper.getMainLooper())
    private var ghost: ExoPlayer? = null
    private var preparedFor: String? = null
    private var active = false
    private var expectedId: String? = null
    private var durMs = 6000L
    private var ghostGain = 1f

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Exception) { cancel() }
            h.postDelayed(this, if (enabled()) 50L else 500L)
        }
    }

    fun start() { h.removeCallbacks(tick); h.postDelayed(tick, 500L) }

    private fun enabled() = AudioFx.prefs(ctx).getBoolean("xfade_on", false)

    private fun step() {
        if (!enabled()) { if (active || ghost != null) cancel(); return }
        val d = (AudioFx.prefs(ctx).getInt("xfade_s", 6) * 1000L).coerceIn(1000L, 12000L)
        if (active) { progress(); return }
        if (!main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
        val dur = main.duration
        if (dur == C.TIME_UNSET || dur < d * 3) return
        val id = main.currentMediaItem?.mediaId ?: return
        val remaining = dur - main.currentPosition
        if (remaining <= d + 4000 && preparedFor != id) prepareGhost(id)
        if (remaining <= d && preparedFor == id) begin(d, id)
    }

    private fun prepareGhost(id: String) {
        val item = main.currentMediaItem ?: return
        val g = ghost ?: ExoPlayer.Builder(ctx, factory)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build().also { ghost = it }
        try { g.audioSessionId = main.audioSessionId } catch (e: Exception) { }
        g.volume = 0f
        g.playWhenReady = false
        g.setMediaItem(item)
        g.prepare()
        preparedFor = id
    }

    private fun begin(d: Long, oldId: String) {
        val g = ghost ?: return
        ghostGain = AudioFx.rgFor(ctx, oldId)
        g.playbackParameters = main.playbackParameters
        g.seekTo(main.currentPosition + 60)
        g.volume = ghostGain
        g.playWhenReady = true
        durMs = d
        active = true
        AudioFx.xfMain = 0f
        main.seekToNextMediaItem()
        expectedId = main.currentMediaItem?.mediaId
        AudioFx.refreshVolume()
    }

    private fun progress() {
        val g = ghost
        val id = main.currentMediaItem?.mediaId
        val p = main.currentPosition
        if (g == null || id != expectedId || p < 0 || p > durMs + 2000) { cancel(); return }
        val x = (p.toFloat() / durMs).coerceIn(0f, 1f)
        if (!main.isPlaying) g.pause() else if (!g.isPlaying) g.play()
        g.volume = Math.cos(x * Math.PI / 2).toFloat() * ghostGain
        AudioFx.xfMain = Math.sin(x * Math.PI / 2).toFloat()
        AudioFx.refreshVolume()
        if (x >= 1f || g.playbackState == Player.STATE_ENDED) cancel()
    }

    /** Termina el crossfade (o lo aborta si el usuario saltó de canción) y deja todo en estado normal. */
    private fun cancel() {
        active = false; preparedFor = null; expectedId = null
        try { ghost?.run { stop(); clearMediaItems() } } catch (e: Exception) { }
        AudioFx.xfMain = 1f
        AudioFx.refreshVolume()
    }

    fun release() {
        h.removeCallbacks(tick)
        cancel()
        try { ghost?.release() } catch (e: Exception) { }
        ghost = null
    }
}
