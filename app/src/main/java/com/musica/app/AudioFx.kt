package com.musica.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.Metadata
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/** Estado de audio compartido entre el servicio y la pantalla de ajustes (mismo proceso). */
object AudioFx {
    @Volatile var eq: Equalizer? = null
    @Volatile var bass: BassBoost? = null
    @Volatile var loud: LoudnessEnhancer? = null
    @Volatile var appCtx: Context? = null
    class Rg(val track: Float?, val album: Float?)
    private val rgByItem = java.util.concurrent.ConcurrentHashMap<String, Rg>()
    private val numRe = Regex("(-?\\d+(?:[.,]\\d+)?)")

    fun prefs(ctx: Context) = ctx.getSharedPreferences("musica_prefs", Context.MODE_PRIVATE)

    fun attach(ctx: Context, sessionId: Int) {
        try { eq?.release() } catch (e: Exception) { }
        try { bass?.release() } catch (e: Exception) { }
        try { loud?.release() } catch (e: Exception) { }
        eq = try { Equalizer(0, sessionId) } catch (e: Exception) { null }
        bass = try { BassBoost(0, sessionId) } catch (e: Exception) { null }
        loud = try { LoudnessEnhancer(sessionId) } catch (e: Exception) { null }
        applyEq(ctx)
        applyMode(ctx)
    }

    @Volatile var player: Player? = null
    @Volatile var sleepEnd = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val sleepTask = Runnable { player?.pause(); sleepEnd = 0L }

    fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepTask)
        if (minutes > 0) {
            sleepEnd = System.currentTimeMillis() + minutes * 60000L
            handler.postDelayed(sleepTask, minutes * 60000L)
        } else sleepEnd = 0L
    }

    fun release() {
        handler.removeCallbacks(volTask)
        try { bass?.release() } catch (e: Exception) { }
        try { loud?.release() } catch (e: Exception) { }
        bass = null; loud = null
        try { eq?.release() } catch (e: Exception) { }
        eq = null
    }

    /** El ecualizador solo actúa en modo envolvente; en Hi-Fi puro queda desactivado. */
    /** Graves y loudness: solo en modo envolvente. */
    fun applyFx(ctx: Context) {
        val p = prefs(ctx)
        val on = p.getString("mode", "hifi") == "surround"
        try {
            bass?.let { b ->
                val st = p.getInt("bass", 0)
                if (b.strengthSupported) b.setStrength(st.toShort())
                b.enabled = on && st > 0
            }
        } catch (e: Exception) { }
        try {
            loud?.let { l ->
                val g = p.getInt("loud", 0)
                l.setTargetGain(g)
                l.enabled = on && g > 0
            }
        } catch (e: Exception) { }
    }

    /** Lee ReplayGain de las etiquetas del archivo (ID3, Vorbis...). */
    fun readReplayGain(id: String?, md: Metadata) {
        if (id == null) return
        var track = rgByItem[id]?.track
        var album = rgByItem[id]?.album
        for (i in 0 until md.length()) {
            val s = md.get(i).toString()
            val low = s.lowercase()
            for ((key, isTrack) in listOf("replaygain_track_gain" to true, "replaygain_album_gain" to false)) {
                val idx = low.indexOf(key)
                if (idx < 0) continue
                val g = numRe.find(s.substring(idx + key.length))?.value?.replace(',', '.')?.toFloatOrNull() ?: continue
                if (isTrack) track = g else album = g
            }
        }
        rgByItem[id] = Rg(track, album)
    }

    private fun rgFactor(ctx: Context, id: String?): Float {
        val mode = prefs(ctx).getString("rg", "off")
        val rg = if (id == null) null else rgByItem[id]
        val g = when (mode) {
            "album" -> rg?.album ?: rg?.track
            "track" -> rg?.track ?: rg?.album
            else -> null
        } ?: return 1f
        return Math.pow(10.0, g / 20.0).toFloat().coerceIn(0.05f, 1f)
    }

    private val volTask = object : Runnable {
        override fun run() { updateVolume(); handler.postDelayed(this, 100) }
    }

    /** Volumen = ReplayGain x fundido de entrada/salida. */
    private fun updateVolume() {
        val p = player ?: return
        val c = appCtx ?: return
        var v = rgFactor(c, p.currentMediaItem?.mediaId)
        val fade = prefs(c).getInt("fade_s", 0)
        if (fade > 0 && p.isPlaying) {
            val dur = p.duration
            if (dur != C.TIME_UNSET && dur > 0) {
                val f = fade * 1000f
                v *= minOf(1f, p.currentPosition / f, (dur - p.currentPosition) / f).coerceIn(0f, 1f)
            }
        }
        if (Math.abs(p.volume - v) > 0.01f) p.volume = v
    }

    fun startVolume(ctx: Context) {
        appCtx = ctx
        handler.removeCallbacks(volTask)
        handler.post(volTask)
    }

    fun applyEq(ctx: Context) {
        applyFx(ctx)
        val e = eq ?: return
        val p = prefs(ctx)
        try {
            val min = e.bandLevelRange[0].toInt(); val max = e.bandLevelRange[1].toInt()
            for (i in 0 until e.numberOfBands.toInt())
                e.setBandLevel(i.toShort(), p.getInt("band$i", 0).coerceIn(min, max).toShort())
            e.enabled = p.getString("mode", "hifi") == "surround" && p.getBoolean("eq_on", false)
        } catch (ex: Exception) { }
    }

    /** Hi-Fi: intenta salida bit-perfect (Android 14+, DAC USB). Envolvente: la desactiva. */
    fun applyMode(ctx: Context): String {
        val hifi = prefs(ctx).getString("mode", "hifi") == "hifi"
        if (Build.VERSION.SDK_INT < 34)
            return if (hifi) "Salida Hi-Fi sin efectos (bit-perfect requiere Android 14 o superior)"
                   else "Modo envolvente: el sistema puede aplicar Dolby Atmos"
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            if (dev == null) {
                if (hifi) "Hi-Fi sin efectos. Conecta un DAC USB para salida bit-perfect"
                else "Modo envolvente: el sistema puede aplicar Dolby Atmos"
            } else if (!hifi) {
                am.clearPreferredMixerAttributes(attrs, dev)
                "Modo envolvente: el sistema puede aplicar Dolby Atmos"
            } else {
                val mix = am.getSupportedMixerAttributes(dev).firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                if (mix != null && am.setPreferredMixerAttributes(attrs, dev, mix))
                    "Bit-perfect activo en ${dev.productName}"
                else "Tu DAC no ofrece bit-perfect; se usa salida Hi-Fi estándar"
            }
        } catch (e: Exception) { "No se pudo configurar bit-perfect en este equipo" }
    }

    fun spatialStatus(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 32) return "Audio espacial de Android: requiere Android 12L o superior"
        val sp = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).spatializer
        return when {
            sp.isAvailable && sp.isEnabled -> "Audio espacial del sistema: activo"
            sp.isAvailable -> "Audio espacial del sistema: disponible, desactivado"
            else -> "Audio espacial de Android: no disponible (Dolby Atmos se activa en los ajustes del sistema)"
        }
    }
}
