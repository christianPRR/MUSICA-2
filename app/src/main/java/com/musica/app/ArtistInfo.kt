package com.musica.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class ArtistBio(val title: String, val extract: String, val url: String, val curiosity: String?, val lang: String)
class DiscoItem(val name: String, val year: String)

/** Biografía (Wikipedia, CC BY-SA) y discografía (iTunes) del artista. Se guarda una copia para verla sin internet. */
object ArtistInfo {
    private val U8 = Charsets.UTF_8
    private val musicWords = listOf("música", "musical", "cantante", "banda", "grupo", "compositor", "músico", "rapero",
        "productor", "singer", "band", "musician", "songwriter", "rapper", "composer", "dj")

    private fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun cacheFile(ctx: Context, artist: String) = File(ctx.filesDir, "bios/${norm(artist).take(60)}.json")

    suspend fun bio(ctx: Context, artist: String): ArtistBio? = withContext(Dispatchers.IO) {
        val cf = cacheFile(ctx, artist)
        try {
            if (cf.exists() && System.currentTimeMillis() - cf.lastModified() < 30L * 86400000L) {
                val o = JSONObject(cf.readText())
                return@withContext ArtistBio(o.getString("title"), o.getString("extract"), o.getString("url"),
                    o.optString("cur").takeIf { it.isNotBlank() }, o.optString("lang"))
            }
        } catch (e: Exception) { }
        val r = fetch("es", artist, "$artist música") ?: fetch("en", artist, "$artist band singer musician")
        if (r != null) try {
            cf.parentFile?.mkdirs()
            cf.writeText(JSONObject().put("title", r.title).put("extract", r.extract).put("url", r.url)
                .put("cur", r.curiosity ?: "").put("lang", r.lang).toString())
        } catch (e: Exception) { }
        r
    }

    private fun fetch(lang: String, artist: String, query: String): ArtistBio? {
        try {
            val url = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=${enc(query)}" +
                "&gsrlimit=5&prop=extracts%7Cinfo&exintro=1&explaintext=1&exsentences=8&inprop=url&redirects=1&format=json"
            val raw = Repo.http(url) ?: return null
            val pages = JSONObject(String(raw, U8)).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val list = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }.toList()
            val na = norm(artist)
            for (p in list) {
                val title = p.optString("title"); val ex = p.optString("extract")
                if (ex.length < 80 || na.isEmpty()) continue
                val nt = norm(title)
                val nameOk = nt.contains(na) || na.contains(nt) || norm(ex.take(300)).contains(na)
                val low = ex.lowercase()
                if (!nameOk || musicWords.none { low.contains(it) }) continue
                val sents = ex.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 25 }
                if (sents.isEmpty()) continue
                val bio = sents.take(4).joinToString(" ")
                val rest = sents.drop(4)
                val cur = (rest.firstOrNull { s -> s.any { it.isDigit() } } ?: rest.firstOrNull())?.take(320)
                val link = p.optString("fullurl").ifBlank { "https://$lang.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}" }
                return ArtistBio(title, bio, link, cur, lang)
            }
        } catch (e: Exception) { }
        return null
    }

    suspend fun discography(artist: String): List<DiscoItem> = withContext(Dispatchers.IO) {
        try {
            val raw = Repo.http("https://itunes.apple.com/search?term=${enc(artist)}&entity=album&limit=40")
                ?: return@withContext emptyList()
            val arr = JSONObject(String(raw, U8)).optJSONArray("results") ?: return@withContext emptyList()
            val na = norm(artist)
            val out = ArrayList<DiscoItem>(); val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = norm(o.optString("artistName"))
                if (a.isEmpty() || !(a == na || a.contains(na))) continue
                val name = o.optString("collectionName").replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").trim()
                if (name.isEmpty() || !seen.add(norm(name))) continue
                out.add(DiscoItem(name, o.optString("releaseDate").take(4)))
            }
            out.sortedBy { it.year }.take(14)
        } catch (e: Exception) { emptyList() }
    }
}
