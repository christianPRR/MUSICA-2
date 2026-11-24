package com.musica.app

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

class TagInfo(
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val genre: String? = null, val year: String? = null, val track: String? = null,
    val albumArtist: String? = null, val lyrics: String? = null, val cover: ByteArray? = null
) {
    fun isEmpty() = listOf(title, artist, album, genre, year, track, albumArtist, lyrics).all { it.isNullOrBlank() } && cover == null
}

/** Lee y escribe etiquetas dentro de los archivos de audio (requiere acceso a todos los archivos). */
object Tags {
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun accessIntent(ctx: Context): Intent {
        val uri = Uri.parse("package:${ctx.packageName}")
        val i = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun readLyrics(path: String): String? = try {
        if (path.isBlank()) null
        else AudioFileIO.read(File(path)).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    fun write(ctx: Context, s: Song, t: TagInfo): Boolean {
        if (t.isEmpty()) return true
        if (s.path.isBlank()) return false
        return try {
            val f = File(s.path)
            val af = AudioFileIO.read(f)
            val tag = af.tagOrCreateAndSetDefault
            fun put(k: FieldKey, v: String?) { if (!v.isNullOrBlank()) tag.setField(k, v) }
            put(FieldKey.TITLE, t.title); put(FieldKey.ARTIST, t.artist); put(FieldKey.ALBUM, t.album)
            put(FieldKey.GENRE, t.genre); put(FieldKey.YEAR, t.year); put(FieldKey.TRACK, t.track)
            put(FieldKey.ALBUM_ARTIST, t.albumArtist); put(FieldKey.LYRICS, t.lyrics)
            val ext = f.extension.lowercase()
            val bytes = t.cover
            // La portada incrustada funciona en MP3 y M4A; en FLAC/OGG se guarda solo dentro de la app.
            if (bytes != null && (ext == "mp3" || ext == "m4a" || ext == "mp4")) {
                val art = ArtworkFactory.getNew()
                art.setBinaryData(bytes)
                art.setMimeType("image/jpeg")
                art.setPictureType(3)
                art.setDescription("")
                tag.deleteArtworkField()
                tag.setField(art)
            }
            af.commit()
            MediaScannerConnection.scanFile(ctx, arrayOf(s.path), null, null)
            true
        } catch (e: Exception) { false }
    }
}
