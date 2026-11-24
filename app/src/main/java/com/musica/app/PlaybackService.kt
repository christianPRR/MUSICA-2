package com.musica.app

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var tracker: PlayTracker? = null
    private var crossfader: Crossfader? = null

    override fun onCreate() {
        super.onCreate()
        // Decodificadores del sistema (FLAC, MP3, M4A, OGG...) + FFmpeg de respaldo
        val prefs = AudioFx.prefs(this)
        val useFfmpeg = prefs.getBoolean("ffmpeg", true)
        val base: DefaultRenderersFactory =
            if (useFfmpeg) try { NextRenderersFactory(this) } catch (t: Throwable) { DefaultRenderersFactory(this) }
            else DefaultRenderersFactory(this)
        // La salida float de 32 bits distorsiona en algunos teléfonos, por eso viene apagada
        val renderers = base.setEnableAudioFloatOutput(prefs.getBoolean("float_out", false))
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                                                   initializedTimestampMs: Long, initializationDurationMs: Long) {
                AudioFx.decoder = decoderName
            }
        })
        val tr = PlayTracker(this, player)
        tracker = tr
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(applicationContext, audioSessionId)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) AudioFx.applyEq(applicationContext)
                tr.onPlayingChanged(isPlaying)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                tr.onTransition(mediaItem)
            }
            override fun onMetadata(metadata: Metadata) {
                AudioFx.readReplayGain(player.currentMediaItem?.mediaId, metadata)
            }
            override fun onTracksChanged(tracks: Tracks) {
                val id = player.currentMediaItem?.mediaId
                for (g in tracks.groups) if (g.type == C.TRACK_TYPE_AUDIO)
                    for (i in 0 until g.length) g.getTrackFormat(i).metadata?.let { AudioFx.readReplayGain(id, it) }
            }
        })
        AudioFx.player = player
        try {
            AudioFx.attach(applicationContext, player.audioSessionId)
            AudioFx.startVolume(applicationContext)
        } catch (t: Throwable) { }
        tr.start()
        crossfader = Crossfader(this, player, renderers).also { it.start() }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        tracker?.stop(); tracker = null
        crossfader?.release(); crossfader = null
        AudioFx.player = null
        AudioFx.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
