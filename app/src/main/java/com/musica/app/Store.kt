package com.musica.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favoritas, listas de reproducción y última sesión, guardadas en el teléfono. */
object Store {
    fun favs(ctx: Context): Set<Long> =
        AudioFx.prefs(ctx).getString("favs", "")!!.split(",").mapNotNull { it.toLongOrNull() }.toSet()

    fun saveFavs(ctx: Context, f: Set<Long>) =
        AudioFx.prefs(ctx).edit().putString("favs", f.joinToString(",")).apply()

    fun playlists(ctx: Context): Map<String, List<Long>> {
        val out = LinkedHashMap<String, List<Long>>()
        try {
            val o = JSONObject(AudioFx.prefs(ctx).getString("playlists", "{}")!!)
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                out[k] = (0 until a.length()).map { a.getLong(it) }
            }
        } catch (e: Exception) { }
        return out
    }

    fun savePlaylists(ctx: Context, m: Map<String, List<Long>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        AudioFx.prefs(ctx).edit().putString("playlists", o.toString()).apply()
    }

    fun saveResume(ctx: Context, ids: List<Long>, index: Int, pos: Long) =
        AudioFx.prefs(ctx).edit().putString("resume_ids", ids.joinToString(","))
            .putInt("resume_idx", index).putLong("resume_pos", pos).apply()

    fun loadResume(ctx: Context): Triple<List<Long>, Int, Long> {
        val p = AudioFx.prefs(ctx)
        val ids = p.getString("resume_ids", "")!!.split(",").mapNotNull { it.toLongOrNull() }
        return Triple(ids, p.getInt("resume_idx", 0), p.getLong("resume_pos", 0L))
    }
}
