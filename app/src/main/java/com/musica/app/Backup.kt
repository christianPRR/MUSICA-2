package com.musica.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Restored(val prefs: Int, val favs: Int, val lists: Int, val events: Int, val missing: Int) {
    fun summary(): String =
        "Restaurado: $prefs ajustes, $favs favoritas, $lists listas y $events registros de historial" +
            if (missing > 0) ". $missing canciones no se encontraron en este teléfono." else "."
}

/**
 * Copia de seguridad en un archivo JSON que tú eliges dónde guardar (Drive, Descargas, otro teléfono...).
 * Las canciones se guardan con título, artista y ruta, para reconocerlas aunque Android cambie sus números internos.
 */
object Backup {
    private fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }

    private fun ref(s: Song?, id: Long): JSONObject =
        if (s == null) JSONObject().put("id", id)
        else JSONObject().put("id", s.id).put("t", s.title).put("a", s.artist).put("p", s.path).put("d", s.durationMs)

    fun export(ctx: Context, songs: List<Song>): String {
        val byId = songs.associateBy { it.id }
        val root = JSONObject()
        root.put("app", "Musica"); root.put("version", 1); root.put("date", System.currentTimeMillis())
        val prefs = JSONObject()
        for ((k, v) in AudioFx.prefs(ctx).all) {
            if (k.startsWith("resume_") || k == "favs" || k == "playlists" || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            prefs.put(k, o)
        }
        root.put("prefs", prefs)
        val favs = JSONArray()
        for (id in Store.favs(ctx)) favs.put(ref(byId[id], id))
        root.put("favs", favs)
        val lists = JSONObject()
        for ((name, ids) in Store.playlists(ctx)) {
            val a = JSONArray(); for (id in ids) a.put(ref(byId[id], id)); lists.put(name, a)
        }
        root.put("playlists", lists)
        val ev = JSONArray()
        for (e in Stats.all(ctx)) ev.put(JSONObject().put("i", e.id).put("t", e.title).put("a", e.artist).put("b", e.album)
            .put("s", e.ts).put("m", e.ms).put("c", if (e.counted) 1 else 0))
        root.put("history", ev)
        return root.toString()
    }

    fun import(ctx: Context, text: String, songs: List<Song>): Restored? {
        return try {
            val root = JSONObject(text)
            if (root.optString("app") != "Musica") return null
            val byId = songs.associateBy { it.id }
            val byPath = songs.filter { it.path.isNotBlank() }.associateBy { it.path }
            val byTA = songs.associateBy { norm(it.title) + "|" + norm(it.artist) }
            var missing = 0
            fun resolve(o: JSONObject): Long? {
                val id = o.optLong("id", -1L)
                val t = o.optString("t"); val a = o.optString("a")
                val s = (byId[id]?.takeIf { t.isEmpty() || norm(it.title) == norm(t) })
                    ?: byPath[o.optString("p")] ?: byTA[norm(t) + "|" + norm(a)]
                if (s == null) missing++
                return s?.id
            }
            // ajustes
            val po = root.optJSONObject("prefs") ?: JSONObject()
            val ed = AudioFx.prefs(ctx).edit()
            var np = 0
            for (k in po.keys()) {
                val o = po.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> ed.putBoolean(k, o.getBoolean("v"))
                    "i" -> ed.putInt(k, o.getInt("v"))
                    "l" -> ed.putLong(k, o.getLong("v"))
                    "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> ed.putString(k, o.getString("v"))
                    "ss" -> { val a = o.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    else -> continue
                }
                np++
            }
            ed.apply()
            // favoritas (se suman a las actuales)
            val fa = root.optJSONArray("favs") ?: JSONArray()
            val favSet = Store.favs(ctx).toMutableSet()
            var nf = 0
            for (i in 0 until fa.length()) resolve(fa.getJSONObject(i))?.let { favSet.add(it); nf++ }
            Store.saveFavs(ctx, favSet)
            // listas (las del archivo reemplazan a las del mismo nombre)
            val lo = root.optJSONObject("playlists") ?: JSONObject()
            val lists = LinkedHashMap(Store.playlists(ctx))
            for (name in lo.keys()) {
                val a = lo.getJSONArray(name)
                lists[name] = (0 until a.length()).mapNotNull { resolve(a.getJSONObject(it)) }
            }
            Store.savePlaylists(ctx, lists)
            // historial (se une al actual sin duplicar)
            val ha = root.optJSONArray("history") ?: JSONArray()
            val merged = Stats.all(ctx).toMutableList()
            val seen = merged.map { "${it.ts}:${it.id}" }.toMutableSet()
            var ne = 0
            for (i in 0 until ha.length()) {
                val o = ha.getJSONObject(i)
                val newId = byTitle(byTA, o.optString("t"), o.optString("a")) ?: o.getLong("i")
                val key = "${o.getLong("s")}:$newId"
                if (!seen.add(key)) continue
                merged.add(PlayEv(newId, o.optString("t"), o.optString("a"), o.optString("b"),
                    o.getLong("s"), o.getLong("m"), o.optInt("c") == 1)); ne++
            }
            Stats.replaceAll(ctx, merged)
            ThemeState.load(ctx)
            Restored(np, nf, lo.length(), ne, missing)
        } catch (e: Exception) { null }
    }

    private fun byTitle(byTA: Map<String, Song>, t: String, a: String): Long? = byTA[norm(t) + "|" + norm(a)]?.id

    /** Lista en formato M3U para abrirla en otros reproductores. */
    fun m3u(name: String, list: List<Song>): String {
        val sb = StringBuilder("#EXTM3U\n#PLAYLIST:$name\n")
        for (s in list) {
            sb.append("#EXTINF:").append(s.durationMs / 1000).append(',').append(s.artist).append(" - ").append(s.title).append('\n')
            sb.append(s.path).append('\n')
        }
        return sb.toString()
    }
}
