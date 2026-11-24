package com.musica.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.exoplayer.ExoPlayer

/**
 * Estado de audio compartido entre el servicio y la interfaz (mismo proceso).
 *
 * Regla de diseño: en modo Hi-Fi la app NO crea ningún efecto de audio (ecualizador, graves, loudness).
 * Solo en modo Envolvente se crean y se anuncian al sistema, para que Dolby Atmos u otros ecualizadores
 * del sistema puedan convivir con ellos en vez de pelearse por la misma sesión.
 */
object AudioFx {
    @Volatile var eq: Equalizer? = null
    @Volatile var bass: BassBoost? = null
    @Volatile var loud: LoudnessEnhancer? = null
    @Volatile var appCtx: Context? = null
    @Volatile var decoder: String = ""
    /** Factor de volumen de la canción entrante durante un crossfade (1 = sin efecto). */
    @Volatile var xfMain: Float = 1f
    /** Cambia cada vez que se crean o destruyen los efectos, para que Ajustes se refresque. */
    var fxVersion by mutableIntStateOf(0)

    class Rg(val track: Float?, val album: Float?)
    private val rgByItem = java.util.concurrent.ConcurrentHashMap<String, Rg>()
    private val numRe = Regex("(-?\\d+(?:[.,]\\d+)?)")

    fun prefs(ctx: Context) = ctx.getSharedPreferences("musica_prefs", Context.MODE_PRIVATE)

    @Volatile private var attachedSid = 0
    @Volatile private var announcedSid = 0
    @Volatile private var reattaching = false

    private fun surround(ctx: Context) = prefs(ctx).getString("mode", "hifi") == "surround"

    private fun announce(ctx: Context, sid: Int, open: Boolean) {
        try {
            val i = Intent(
                if (open) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sid)
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            ctx.sendBroadcast(i)
        } catch (e: Exception) { }
    }

    private fun createEffects(ctx: Context, sid: Int) {
        if (sid == 0) return
        eq = try { Equalizer(0, sid) } catch (e: Exception) { null }
        bass = try { BassBoost(0, sid) } catch (e: Exception) { null }
        loud = try { LoudnessEnhancer(sid) } catch (e: Exception) { null }
        announce(ctx, sid, true)
        announcedSid = sid
        fxVersion++
    }

    private fun releaseEffects(ctx: Context) {
        try { eq?.release() } catch (e: Exception) { }
        try { bass?.release() } catch (e: Exception) { }
        try { loud?.release() } catch (e: Exception) { }
        eq = null; bass = null; loud = null
        if (announcedSid != 0) { announce(ctx, announcedSid, false); announcedSid = 0 }
        fxVersion++
    }

    /** Crea o destruye los efectos según el modo actual. */
    private fun ensureEffects(ctx: Context) {
        val s = surround(ctx)
        val has = eq != null || bass != null || loud != null
        if (s && !has) {
            val sid = if (attachedSid != 0) attachedSid else (player?.audioSessionId ?: 0)
            if (sid != 0 && !reattaching) {
                reattaching = true
                try { attachedSid = sid; createEffects(ctx, sid) } finally { reattaching = false }
            }
        } else if (!s && has) {
            releaseEffects(ctx)
        }
    }

    fun attach(ctx: Context, sessionId: Int) {
        appCtx = ctx
        releaseEffects(ctx)
        attachedSid = sessionId
        applyEq(ctx)
        applyMode(ctx)
    }

    @Volatile var player: ExoPlayer? = null
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
        appCtx?.let { releaseEffects(it) }
        attachedSid = 0
    }

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

    /** Factor de volumen por ReplayGain para una canción (1 si está apagado o no hay etiquetas). */
    fun rgFor(ctx: Context, id: String?): Float {
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

    fun refreshVolume() = updateVolume()

    /** Volumen = ReplayGain x fundido de borde x entrada del crossfade. */
    private fun updateVolume() {
        val p = player ?: return
        val c = appCtx ?: return
        val pr = prefs(c)
        var v = rgFor(c, p.currentMediaItem?.mediaId)
        val fade = if (pr.getBoolean("xfade_on", false)) 0 else pr.getInt("fade_s", 0)
        if (fade > 0 && p.isPlaying) {
            val dur = p.duration
            if (dur != C.TIME_UNSET && dur > 0) {
                val f = fade * 1000f
                v *= minOf(1f, p.currentPosition / f, (dur - p.currentPosition) / f).coerceIn(0f, 1f)
            }
        }
        v *= xfMain
        if (Math.abs(p.volume - v) > 0.005f) p.volume = v
    }

    fun startVolume(ctx: Context) {
        appCtx = ctx
        handler.removeCallbacks(volTask)
        handler.post(volTask)
    }

    fun applyEq(ctx: Context) {
        ensureEffects(ctx)
        applyFx(ctx)
        val e0 = eq ?: return
        // Si otra app o el sistema (p. ej. Dolby) nos quitó el control del ecualizador, lo recreamos una vez
        if (!reattaching && !e0.hasControl()) {
            reattaching = true
            try {
                val sid = attachedSid
                releaseEffects(ctx)
                createEffects(ctx, sid)
            } finally { reattaching = false }
        }
        val e = eq ?: return
        val p = prefs(ctx)
        try {
            val min = e.bandLevelRange[0].toInt(); val max = e.bandLevelRange[1].toInt()
            for (i in 0 until e.numberOfBands.toInt())
                e.setBandLevel(i.toShort(), p.getInt("band$i", 0).coerceIn(min, max).toShort())
            e.enabled = p.getString("mode", "hifi") == "surround" && p.getBoolean("eq_on", true)
        } catch (ex: Exception) { }
    }

    /** Cosas que la app hace a la señal y que impiden afirmar que la salida es idéntica al archivo. */
    fun signalAlterations(ctx: Context): List<String> {
        val p = prefs(ctx)
        val out = mutableListOf<String>()
        if (p.getString("rg", "off") != "off") out.add("ReplayGain")
        if (p.getInt("fade_s", 0) > 0 && !p.getBoolean("xfade_on", false)) out.add("fundido")
        if (p.getBoolean("xfade_on", false)) out.add("crossfade")
        if (p.getInt("viz", 0) > 0) out.add("visualizador")
        if (p.getBoolean("float_out", false)) out.add("salida float de 32 bits")
        if ((player?.playbackParameters?.speed ?: 1f) != 1f) out.add("velocidad distinta de 1×")
        return out
    }

    /**
     * Hi-Fi: la app no aplica efectos. En Android 14+ con DAC USB solicita al sistema la ruta bit-perfect.
     * Solo se informa lo que el sistema acepta; nunca se afirma que la reproducción es bit-perfect.
     */
    fun applyMode(ctx: Context): String {
        val hifi = prefs(ctx).getString("mode", "hifi") == "hifi"
        val atmos = "Modo envolvente: el sistema puede aplicar Dolby Atmos u otros efectos."
        if (Build.VERSION.SDK_INT < 34)
            return if (hifi) "Hi-Fi: la app no aplica efectos. Solicitar salida bit-perfect requiere Android 14 o superior y un DAC USB compatible."
                   else atmos
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            if (dev == null) {
                if (hifi) "Hi-Fi: la app no aplica efectos. Conecta un DAC USB para solicitar salida bit-perfect."
                else atmos
            } else if (!hifi) {
                am.clearPreferredMixerAttributes(attrs, dev)
                atmos
            } else {
                val mix = am.getSupportedMixerAttributes(dev).firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                val asked = mix != null && am.setPreferredMixerAttributes(attrs, dev, mix)
                val accepted = asked &&
                    am.getPreferredMixerAttributes(attrs, dev)?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                val alt = signalAlterations(ctx)
                when {
                    !asked -> "Tu DAC (${dev.productName}) no ofrece modo bit-perfect; se usa la salida estándar sin efectos de la app."
                    !accepted -> "Se solicitó la ruta bit-perfect a ${dev.productName}, pero el sistema no la confirmó."
                    alt.isNotEmpty() -> "El sistema aceptó la ruta bit-perfect en ${dev.productName}, pero la app modifica la señal con: ${alt.joinToString(", ")}. Desactívalos para no alterarla."
                    else -> "El sistema aceptó la ruta bit-perfect en ${dev.productName} y la app no aplica procesamiento. La salida final depende del sistema y del DAC, así que no se puede garantizar."
                }
            }
        } catch (e: Exception) { "No se pudo consultar la salida de audio en este equipo." }
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

    /** Aviso para evitar duplicar procesamiento entre el ecualizador de la app y los efectos del sistema. */
    fun conflictNote(ctx: Context): String {
        if (!surround(ctx)) return "En Hi-Fi la app no usa ecualizador ni efectos, así que no compite con los del sistema."
        val spatial = Build.VERSION.SDK_INT >= 32 &&
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).spatializer.let { it.isAvailable && it.isEnabled }
        return if (spatial)
            "El audio espacial del sistema está activo: el ecualizador de la app se suma a su procesamiento. Si el sonido se oye exagerado, apaga el ecualizador de la app o usa el del sistema."
        else
            "Si activas Dolby Atmos u otro ecualizador del sistema, evita ajustar también el de la app para no duplicar el efecto."
    }

    /** Abre el panel de efectos del sistema para la sesión actual (si el teléfono tiene uno). */
    fun openSystemEq(ctx: Context): Boolean {
        val sid = attachedSid.takeIf { it != 0 } ?: (player?.audioSessionId ?: 0)
        return try {
            val i = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sid)
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) { false }
        catch (e: Exception) { false }
    }
}
