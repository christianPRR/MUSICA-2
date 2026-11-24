package com.musica.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Un tramo de escucha. `counted` = true cuando con él la canción pasó a contar como una reproducción. */
class PlayEv(val id: Long, val title: String, val artist: String, val album: String,
             val ts: Long, val ms: Long, val counted: Boolean)

class Rank(val key: String, val sub: String, val plays: Int, val ms: Long, val songId: Long?)

/** Historial de escucha guardado en el teléfono (un JSON por línea). */
object Stats {
    private val lock = Any()
    private var cache: MutableList<PlayEv>? = null
    /** Sube con cada registro nuevo para refrescar las pantallas. */
    var version by mutableIntStateOf(0)

    private fun file(ctx: Context) = File(ctx.filesDir, "history.jsonl")

    private fun toJson(e: PlayEv): String = JSONObject()
        .put("i", e.id).put("t", e.title).put("a", e.artist).put("b", e.album)
        .put("s", e.ts).put("m", e.ms).put("c", if (e.counted) 1 else 0).toString()

    private fun load(ctx: Context): MutableList<PlayEv> {
        cache?.let { return it }
        val out = mutableListOf<PlayEv>()
        try {
            val f = file(ctx)
            if (f.exists()) f.forEachLine { line ->
                try {
                    val o = JSONObject(line)
                    out.add(PlayEv(o.getLong("i"), o.optString("t"), o.optString("a"), o.optString("b"),
                        o.getLong("s"), o.getLong("m"), o.optInt("c") == 1))
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        cache = out
        return out
    }

    fun all(ctx: Context): List<PlayEv> = synchronized(lock) { load(ctx).toList() }

    fun record(ctx: Context, e: PlayEv) {
        synchronized(lock) {
            load(ctx).add(e)
            try { file(ctx).appendText(toJson(e) + "\n") } catch (x: Exception) { }
        }
        version++
    }

    /** Reemplaza todo el historial (usado al restaurar una copia). */
    fun replaceAll(ctx: Context, list: List<PlayEv>) {
        synchronized(lock) {
            val m = list.sortedBy { it.ts }.toMutableList()
            cache = m
            try { file(ctx).writeText(m.joinToString("") { toJson(it) + "\n" }) } catch (x: Exception) { }
        }
        version++
    }

    fun clear(ctx: Context) = replaceAll(ctx, emptyList())

    // ---- cálculos ----
    fun dayStart(ts: Long): Long {
        val c = Calendar.getInstance(); c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** period: 0 = últimos 7 días, 1 = últimos 30 días, 2 = todo. */
    fun inPeriod(all: List<PlayEv>, period: Int): List<PlayEv> {
        if (period >= 2) return all
        val days = if (period == 0) 6 else 29
        val from = dayStart(System.currentTimeMillis()) - days * 86400000L
        return all.filter { it.ts >= from }
    }

    private fun rank(evs: List<PlayEv>, keyOf: (PlayEv) -> String, subOf: (PlayEv) -> String): List<Rank> =
        evs.groupBy(keyOf).map { (k, list) ->
            Rank(k, subOf(list.last()), list.count { it.counted }, list.sumOf { it.ms }, list.last().id)
        }.filter { it.plays > 0 }.sortedWith(compareByDescending<Rank> { it.plays }.thenByDescending { it.ms })

    fun topSongs(evs: List<PlayEv>): List<Rank> =
        rank(evs, { it.id.toString() }, { it.artist }).map { r ->
            val e = evs.last { it.id.toString() == r.key }
            Rank(e.title, e.artist, r.plays, r.ms, e.id)
        }

    fun topArtists(evs: List<PlayEv>): List<Rank> =
        rank(evs.filter { it.artist.isNotBlank() }, { it.artist }, { it.album })
            .map { Rank(it.key, "", it.plays, it.ms, it.songId) }

    fun topAlbums(evs: List<PlayEv>): List<Rank> =
        rank(evs.filter { it.album.isNotBlank() }, { it.album + "\u0000" + it.artist }, { it.artist })
            .map { Rank(it.key.substringBefore('\u0000'), it.sub, it.plays, it.ms, it.songId) }

    fun totalMs(evs: List<PlayEv>) = evs.sumOf { it.ms }
    fun playCount(evs: List<PlayEv>) = evs.count { it.counted }

    /** Milisegundos escuchados en cada uno de los últimos 7 días (el último es hoy). */
    fun lastSevenDays(all: List<PlayEv>): List<Long> {
        val today = dayStart(System.currentTimeMillis())
        val out = LongArray(7)
        for (e in all) {
            val d = ((today - dayStart(e.ts)) / 86400000L).toInt()
            if (d in 0..6) out[6 - d] += e.ms
        }
        return out.toList()
    }

    fun favoriteHour(evs: List<PlayEv>): Int? {
        val by = IntArray(24)
        for (e in evs) if (e.counted) {
            val c = Calendar.getInstance(); c.timeInMillis = e.ts
            by[c.get(Calendar.HOUR_OF_DAY)]++
        }
        val m = by.max()
        return if (m == 0) null else by.indexOf(m)
    }

    fun fmtDur(ms: Long): String {
        val min = ms / 60000
        return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)} min" else if (min >= 1) "$min min" else "${ms / 1000} s"
    }

    fun summary(evs: List<PlayEv>, label: String): List<String> {
        if (playCount(evs) == 0) return listOf("Aún no hay datos. Escucha música y aquí aparecerá tu resumen.")
        val out = mutableListOf<String>()
        out.add("Escuchaste ${fmtDur(totalMs(evs))} de música $label, en ${playCount(evs)} reproducciones.")
        topArtists(evs).firstOrNull()?.let { out.add("Tu artista más escuchado: ${it.key} (${it.plays}).") }
        topSongs(evs).firstOrNull()?.let { out.add("Tu canción favorita: ${it.key}, ${it.plays} veces.") }
        favoriteHour(evs)?.let { out.add("Sueles escuchar más a las $it:00.") }
        return out
    }
}

/**
 * Cuenta la escucha real dentro del servicio (funciona con la app cerrada).
 * Una reproducción cuenta cuando se han escuchado 30 s, o la mitad si la canción es corta.
 */
class PlayTracker(private val ctx: Context, private val p: ExoPlayer) {
    private val h = Handler(Looper.getMainLooper())
    private var item: MediaItem? = null
    private var acc = 0L          // tiempo sin guardar
    private var cum = 0L          // tiempo acumulado de esta canción
    private var counted = false
    private var lastDur = C.TIME_UNSET

    private val tick = object : Runnable {
        override fun run() {
            if (p.isPlaying) acc += 1000
            val d = p.duration
            if (d != C.TIME_UNSET && d > 0) lastDur = d
            h.postDelayed(this, 1000)
        }
    }

    fun start() { item = p.currentMediaItem; h.removeCallbacks(tick); h.postDelayed(tick, 1000) }

    fun stop() { flush(); h.removeCallbacks(tick) }

    fun onTransition(new: MediaItem?) {
        flush()
        item = new; cum = 0; acc = 0; counted = false; lastDur = C.TIME_UNSET
    }

    fun onPlayingChanged(playing: Boolean) { if (!playing) flush() }

    private fun flush() {
        val cur = item ?: return
        if (acc < 1000) return
        val id = cur.mediaId.toLongOrNull() ?: run { acc = 0; return }
        val thr = if (lastDur != C.TIME_UNSET && lastDur > 0) minOf(30000L, maxOf(lastDur / 2, 5000L)) else 30000L
        cum += acc
        val newly = !counted && cum >= thr
        if (newly) counted = true
        Stats.record(ctx, PlayEv(id, cur.mediaMetadata.title?.toString().orEmpty(),
            cur.mediaMetadata.artist?.toString().orEmpty(), cur.mediaMetadata.albumTitle?.toString().orEmpty(),
            System.currentTimeMillis(), acc, newly))
        acc = 0
    }
}
