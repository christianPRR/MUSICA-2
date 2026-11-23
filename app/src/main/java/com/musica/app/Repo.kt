package com.musica.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Lyrics(val lines: List<Pair<Long, String>>, val synced: Boolean)

object Repo {
    private val U8 = Charsets.UTF_8
    private val tag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    private fun clean(s: String) = if (s.contains("unknown", true)) "" else s
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun coverFile(ctx: Context, s: Song) = File(ctx.filesDir, "covers/${s.id}.jpg")
    private fun lyricsFile(ctx: Context, s: Song) = File(ctx.filesDir, "lyrics/${s.id}.lrc")

    private fun http(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "MUSICA/1.0")
        if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
    } catch (e: Exception) { null }

    private val thumbs = android.util.LruCache<Long, Bitmap>(300)

    suspend fun thumb(ctx: Context, s: Song): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(s.id)?.let { return@withContext it }
        val f = coverFile(ctx, s)
        val b = try {
            if (f.exists()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
            else if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri(s.id), android.util.Size(160, 160), null)
            else null
        } catch (e: Exception) { null }
        if (b != null) thumbs.put(s.id, b)
        b
    }

    /** Busca datos de la canción en iTunes, validando que título y artista coincidan. */
    suspend fun lookup(s: Song): TagInfo? = withContext(Dispatchers.IO) {
        try {
            val q = (clean(s.artist) + " " + s.title).trim()
            val j = http("https://itunes.apple.com/search?term=${enc(q)}&entity=song&limit=5")
                ?: return@withContext null
            val arr = JSONObject(String(j, U8)).optJSONArray("results") ?: return@withContext null
            fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
            val nt = norm(s.title); val na = norm(clean(s.artist))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rt = norm(o.optString("trackName")); val ra = norm(o.optString("artistName"))
                val titleOk = nt.isNotEmpty() && rt.isNotEmpty() && (rt.contains(nt) || nt.contains(rt))
                val artistOk = na.isEmpty() || ra.contains(na) || na.contains(ra)
                if (!titleOk || !artistOk) continue
                val art = o.optString("artworkUrl100")
                val cover = if (art.isNotBlank()) http(art.replace("100x100bb", "600x600bb")) else null
                return@withContext TagInfo(
                    artist = o.optString("artistName"), album = o.optString("collectionName"),
                    genre = o.optString("primaryGenreName"), year = o.optString("releaseDate").take(4),
                    track = o.optInt("trackNumber").takeIf { it > 0 }?.toString(), cover = cover)
            }
            null
        } catch (e: Exception) { null }
    }

    /** Guarda la portada en la app y escribe las etiquetas dentro del archivo. */
    suspend fun saveMeta(ctx: Context, s: Song, info: TagInfo): Boolean = withContext(Dispatchers.IO) {
        info.cover?.let { b ->
            try { val f = coverFile(ctx, s); f.parentFile?.mkdirs(); f.writeBytes(b); thumbs.remove(s.id) } catch (e: Exception) { }
        }
        Tags.write(ctx, s, info)
    }

    /** Actualiza una canción desde internet. Sin sobrescribir, solo rellena lo que falta. */
    suspend fun autoUpdate(ctx: Context, s: Song, overwrite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val o = lookup(s) ?: return@withContext false
        fun unk(x: String) = x.isBlank() || x.contains("unknown", true) || x == "Desconocido"
        val hasCover = loadCover(ctx, s) != null
        val embeddedLyrics = Tags.readLyrics(s.path)
        val info = TagInfo(
            artist = if (overwrite || unk(s.artist)) o.artist else null,
            album = if (overwrite || unk(s.album)) o.album else null,
            genre = if (overwrite || s.genre.isBlank()) o.genre else null,
            year = if (overwrite) o.year else null,
            track = if (overwrite) o.track else null,
            cover = if (overwrite || !hasCover) o.cover else null,
            lyrics = if (embeddedLyrics.isNullOrBlank()) loadLyrics(ctx, s, false) else null)
        saveMeta(ctx, s, info)
    }

    /** Calidad del archivo: códec, bits, frecuencia y tasa, p. ej. "FLAC · 24-bit · 96 kHz". */
    suspend fun quality(ctx: Context, s: Song): String = withContext(Dispatchers.IO) {
        try {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri(s.id), null)
            var mime = ""; var rate = 0; var bits = 0; var kbps = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("audio/")) continue
                mime = m
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) bits = when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> 0
                }
                if (f.containsKey(MediaFormat.KEY_BIT_RATE)) kbps = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                break
            }
            ex.release()
            if (kbps == 0 && s.durationMs > 0) {
                val len = ctx.contentResolver.openAssetFileDescriptor(uri(s.id), "r")?.use { it.length } ?: 0L
                if (len > 0) kbps = (len * 8 / (s.durationMs / 1000.0) / 1000).toInt()
            }
            if (mime.isEmpty()) return@withContext ""
            val codec = when (mime) {
                "audio/flac" -> "FLAC"; "audio/mpeg" -> "MP3"; "audio/mp4a-latm" -> "AAC"
                "audio/alac" -> "ALAC"; "audio/opus" -> "OPUS"; "audio/vorbis" -> "OGG"; "audio/raw" -> "WAV"
                else -> mime.substringAfter('/').uppercase()
            }
            val parts = mutableListOf(codec)
            if (bits > 0) parts.add("$bits-bit")
            if (rate > 0) parts.add(java.lang.String.format(java.util.Locale.US, "%.1f kHz", rate / 1000.0).replace(".0 kHz", " kHz"))
            if (kbps > 0) parts.add("$kbps kbps")
            (if (rate >= 88200 || bits >= 24) "Hi-Res · " else "") + parts.joinToString(" · ")
        } catch (e: Exception) { "" }
    }

    /** Portada guardada, o la incrustada en el archivo de audio. */
    suspend fun loadCover(ctx: Context, s: Song): Bitmap? = withContext(Dispatchers.IO) {
        val f = coverFile(ctx, s)
        if (f.exists()) return@withContext BitmapFactory.decodeFile(f.path)
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri(s.id))
            val b = r.embeddedPicture
            r.release()
            b?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) { null }
    }

    /** Descarga la portada desde iTunes y la guarda (reemplaza la anterior). */
    suspend fun downloadCover(ctx: Context, s: Song): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val q = (clean(s.artist) + " " + s.title).trim()
            val j = http("https://itunes.apple.com/search?term=${enc(q)}&entity=song&limit=1")
                ?: return@withContext null
            val u = JSONObject(String(j, U8)).optJSONArray("results")?.optJSONObject(0)
                ?.optString("artworkUrl100").orEmpty()
            if (u.isBlank()) return@withContext null
            val img = http(u.replace("100x100bb", "600x600bb")) ?: return@withContext null
            val bmp = BitmapFactory.decodeByteArray(img, 0, img.size) ?: return@withContext null
            val f = coverFile(ctx, s); f.parentFile?.mkdirs(); f.writeBytes(img)
            bmp
        } catch (e: Exception) { null }
    }

    /** Letra guardada; si no existe (o force=true) la descarga de LRCLIB. */
    suspend fun loadLyrics(ctx: Context, s: Song, force: Boolean): String? = withContext(Dispatchers.IO) {
        val f = lyricsFile(ctx, s)
        if (!force) {
            if (s.path.isNotBlank()) {
                val side = File(s.path.substringBeforeLast('.') + ".lrc")
                if (side.exists()) return@withContext side.readText()
            }
            if (f.exists()) return@withContext f.readText()
            Tags.readLyrics(s.path)?.let { return@withContext it }
        }
        val t = downloadLyrics(s)
        if (t != null) { f.parentFile?.mkdirs(); f.writeText(t) }
        t ?: if (force && f.exists()) f.readText() else null
    }

    private fun pick(o: JSONObject): String? {
        val s = o.optString("syncedLyrics")
        if (s.isNotBlank() && s != "null") return s
        val p = o.optString("plainLyrics")
        return if (p.isNotBlank() && p != "null") p else null
    }

    private fun downloadLyrics(s: Song): String? {
        try {
            val a = enc(clean(s.artist)); val t = enc(s.title); val d = s.durationMs / 1000
            http("https://lrclib.net/api/get?artist_name=$a&track_name=$t&duration=$d")?.let {
                pick(JSONObject(String(it, U8)))?.let { x -> return x }
            }
            val q = enc((clean(s.artist) + " " + s.title).trim())
            val raw = http("https://lrclib.net/api/search?q=$q") ?: return null
            val arr = JSONArray(String(raw, U8))
            for (i in 0 until arr.length()) pick(arr.getJSONObject(i))?.let { return it }
        } catch (e: Exception) { }
        return null
    }

    fun parse(text: String): Lyrics {
        val out = mutableListOf<Pair<Long, String>>()
        for (line in text.lines()) {
            val ms = tag.findAll(line).toList()
            if (ms.isEmpty()) continue
            val txt = line.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val frac = m.groupValues[3]
                val f = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out.add(((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + f) to txt)
            }
        }
        return if (out.isNotEmpty()) Lyrics(out.sortedBy { it.first }, true)
        else Lyrics(text.lines().map { 0L to it.trim() }, false)
    }
}
