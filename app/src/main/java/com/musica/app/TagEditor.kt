package com.musica.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun TagEditorDialog(song: Song, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun clean(x: String) = if (x.contains("unknown", true)) "" else x
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(clean(song.artist)) }
    var album by remember { mutableStateOf(clean(song.album)) }
    var genre by remember { mutableStateOf(song.genre) }
    var year by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Editar etiquetas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true)
                OutlinedTextField(genre, { genre = it }, label = { Text("Género") }, singleLine = true)
                OutlinedTextField(year, { year = it }, label = { Text("Año") }, singleLine = true)
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; status = "Buscando…"
                        val o = Repo.lookup(song.copy(title = title, artist = artist))
                        if (o != null) {
                            artist = o.artist.orEmpty(); album = o.album.orEmpty()
                            genre = o.genre.orEmpty(); year = o.year.orEmpty(); cover = o.cover
                            status = "Datos encontrados: revisa y pulsa Guardar"
                        } else status = "Sin coincidencias"
                        busy = false
                    }
                }) { Text("Buscar en línea") }
                if (!Tags.hasAccess()) Text("Falta el permiso de acceso a todos los archivos (Ajustes de sonido, abajo).")
                if (status.isNotEmpty()) Text(status)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    val ok = Repo.saveMeta(ctx, song, TagInfo(title = title, artist = artist, album = album,
                        genre = genre, year = year, cover = cover))
                    status = if (ok) "Guardado" else "No se pudo escribir en el archivo"
                    busy = false
                    if (ok) onDone()
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}
