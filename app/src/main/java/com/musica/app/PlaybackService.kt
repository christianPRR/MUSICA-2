package com.musica.app

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        // Decodificadores del sistema + FFmpeg de respaldo; salida float de 32 bits para alta resolución
        val base: DefaultRenderersFactory = try { NextRenderersFactory(this) } catch (t: Throwable) { DefaultRenderersFactory(this) }
        val renderers = base.setEnableAudioFloatOutput(true)
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(applicationContext, audioSessionId)
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
        session = MediaSession.Builder(this, player).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        AudioFx.player = null
        AudioFx.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
