package com.musica.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.Normalizer

/** Una emisora de radio por internet. */
data class Station(
    val id: String, val name: String, val url: String, val place: String,
    val lima: Boolean, val clicks: Int, val codec: String, val bitrate: Int
)

/**
 * Lista de radios peruanas. Se descarga del directorio abierto Radio Browser (radio-browser.info),
 * ordenada por oyentes (clics); las de Lima van primero. Se guarda una copia para ver la lista sin conexión.
 */
object RadioRepo {
    private val hosts = listOf("de1", "nl1", "at1")

    // Radios conocidas de Lima: sirven para detectar emisoras limeñas aunque no indiquen la ciudad.
    private val limaNames = listOf(
        "rpp", "radio moda", "ritmo romantica", "panamericana", "exitosa", "studio 92", "oxigeno",
        "la zona", "onda cero", "radiomar", "radio mar", "nueva q", "la kalle", "radio felicidad",
        "radio capital", "karibena", "radio planeta", "radio beat", "radio corazon", "radio libertad",
        "radio melodia", "radio santa rosa", "radio nacional", "radio inca", "radio uno", "radio mega",
        "mega 107", "cuarenta", "los 40", "ok radio", "radio cumbia", "radio disney", "radio san borja",
        "radio oxigeno", "radio el sol", "la mega", "radio maria"
    )

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun file(ctx: Context) = File(ctx.filesDir, "radios_pe.json")

    fun stale(ctx: Context): Boolean {
        val f = file(ctx)
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L
    }

    fun cached(ctx: Context): List<Station>? = try {
        file(ctx).takeIf { it.exists() }?.readText()?.let { parse(it) }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    /** Descarga la lista (en segundo plano). Devuelve null si no hay conexión. */
    fun fetch(ctx: Context): List<Station>? {
        for (h in hosts) {
            val b = Repo.http("https://$h.api.radio-browser.info/json/stations/search" +
                "?countrycode=PE&hidebroken=true&order=clickcount&reverse=true&limit=400") ?: continue
            try {
                val txt = String(b, Charsets.UTF_8)
                val l = parse(txt)
                if (l.isNotEmpty()) { file(ctx).writeText(txt); return l }
            } catch (e: Exception) { }
        }
        return null
    }

    fun parse(txt: String): List<Station> {
        val arr = JSONArray(txt)
        val seen = HashSet<String>()
        val out = ArrayList<Station>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val name = o.optString("name").trim()
            if (url.isBlank() || name.isBlank() || !url.startsWith("http")) continue
            val u = url.lowercase()
            // El reproductor no incluye HLS ni listas de reproducción: se omiten esos enlaces
            if (o.optInt("hls", 0) == 1 || u.contains(".m3u") || u.contains(".pls") || u.contains(".asx")) continue
            if (o.optInt("lastcheckok", 1) == 0) continue
            val key = norm(name)
            if (!seen.add(key)) continue
            val state = o.optString("state").trim()
            val st = norm(state)
            val lima = st.contains("lima") || st.contains("callao") || key.contains("lima") ||
                limaNames.any { key.contains(it) }
            out += Station(
                o.optString("stationuuid").ifBlank { key }, name, url,
                if (lima) "Lima" else state.ifBlank { "Perú" }, lima,
                o.optInt("clickcount", 0), o.optString("codec").uppercase(), o.optInt("bitrate", 0)
            )
        }
        return out.sortedWith(compareByDescending<Station> { it.lima }.thenByDescending { it.clicks })
    }

    fun favs(ctx: Context): Set<String> =
        AudioFx.prefs(ctx).getString("radio_favs", "")!!.split("\n").filter { it.isNotBlank() }.toSet()

    fun saveFavs(ctx: Context, f: Set<String>) {
        AudioFx.prefs(ctx).edit().putString("radio_favs", f.joinToString("\n")).apply()
    }
}

@Composable
fun RadioScreen(c: MediaController?, currentId: String?, playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(RadioRepo.cached(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var favs by remember { mutableStateOf(RadioRepo.favs(ctx)) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (loading) return
        loading = true; failed = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { RadioRepo.fetch(ctx) }
            if (r != null) list = r else failed = true
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (list == null || RadioRepo.stale(ctx)) refresh() }

    DisposableEffect(c) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(e: PlaybackException) { error = "Esta emisora no responde ahora. Prueba otra." }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { error = null }
        }
        c?.addListener(l)
        buffering = c?.playbackState == Player.STATE_BUFFERING
        onDispose { c?.removeListener(l) }
    }

    val isRadio = currentId?.startsWith("radio:") == true
    val now = if (isRadio) list?.firstOrNull { "radio:${it.id}" == currentId } else null

    fun play(s: Station) {
        val cur = c ?: return
        error = null
        if (currentId == "radio:${s.id}") {
            if (playing) cur.pause()
            else { if (cur.playbackState == Player.STATE_IDLE) cur.prepare(); cur.play() }
            return
        }
        val md = MediaMetadata.Builder().setTitle(s.name)
            .setArtist(if (s.lima) "Radio · Lima" else "Radio · Perú").build()
        cur.setPlaybackSpeed(1f)
        cur.setMediaItem(MediaItem.Builder().setMediaId("radio:${s.id}").setUri(s.url).setMediaMetadata(md).build())
        cur.prepare(); cur.play()
    }
    fun toggleFav(s: Station) {
        favs = if (s.id in favs) favs - s.id else favs + s.id
        RadioRepo.saveFavs(ctx, favs)
    }

    val filtered = remember(list, q) {
        val all = list.orEmpty()
        val k = RadioRepo.norm(q.trim())
        if (k.isEmpty()) all else all.filter { RadioRepo.norm(it.name).contains(k) || RadioRepo.norm(it.place).contains(k) }
    }
    val favList = filtered.filter { it.id in favs }
    val lima = filtered.filter { it.lima && it.id !in favs }
    val rest = filtered.filter { !it.lima && it.id !in favs }

    Column(modifier) {
        OutlinedTextField(q, { q = it }, singleLine = true, shape = RoundedCornerShape(16.dp),
            placeholder = { Text("Buscar radio") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))

        if (isRadio) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .glass(RoundedCornerShape(20.dp), 0.14f).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (buffering) "Conectando…" else if (playing) "En vivo" else "En pausa",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(now?.name ?: c?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Radio",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({
                    val cur = c ?: return@IconButton
                    if (playing) cur.pause()
                    else { if (cur.playbackState == Player.STATE_IDLE) cur.prepare(); cur.play() }
                }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Reproducir o pausar") }
                IconButton({ c?.run { stop(); clearMediaItems() } }) { Icon(Icons.Default.Stop, "Detener") }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }

        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loading) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Buscando radios peruanas…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Default.Radio, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(if (failed) "No se pudo cargar la lista. Revisa tu conexión a internet."
                             else "Sin radios todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button({ refresh() }) { Text("Reintentar") }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = LocalBottomPad.current + 16.dp)) {
                if (favList.isNotEmpty()) {
                    item(key = "h_fav") { RadioHeader("Tus favoritas") }
                    items(favList, key = { "f" + it.id }) { s ->
                        StationRow(s, currentId == "radio:${s.id}", playing, buffering, true, { play(s) }, { toggleFav(s) })
                    }
                }
                if (lima.isNotEmpty()) {
                    item(key = "h_lima") { RadioHeader("Lima · más escuchadas") }
                    items(lima, key = { "l" + it.id }) { s ->
                        StationRow(s, currentId == "radio:${s.id}", playing, buffering, false, { play(s) }, { toggleFav(s) })
                    }
                }
                if (rest.isNotEmpty()) {
                    item(key = "h_rest") { RadioHeader("Otras regiones del Perú") }
                    items(rest, key = { "r" + it.id }) { s ->
                        StationRow(s, currentId == "radio:${s.id}", playing, buffering, false, { play(s) }, { toggleFav(s) })
                    }
                }
                if (filtered.isEmpty()) item(key = "empty") {
                    Text("No hay radios con ese nombre.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp))
                }
                item(key = "foot") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Fuente: radio-browser.info", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else TextButton({ refresh() }) { Text("Actualizar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioHeader(t: String) {
    Text(t, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun StationRow(s: Station, current: Boolean, playing: Boolean, buffering: Boolean, fav: Boolean,
                       onPlay: () -> Unit, onFav: () -> Unit) {
    val hue = (s.name.hashCode().toLong() and 0x7fffffff) % 360
    val tint = Color.hsv(hue.toFloat(), 0.45f, 0.55f)
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clickable { onPlay() }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            if (current && playing && !buffering) Icon(Icons.Default.GraphicEq, null, tint = Color.White)
            else if (current && buffering) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(s.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "R",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) primary else MaterialTheme.colorScheme.onSurface)
            val info = listOfNotNull(s.place, s.codec.ifBlank { null },
                if (s.bitrate > 0) "${s.bitrate} kbps" else null).joinToString(" · ")
            Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        IconButton(onFav) {
            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorita",
                tint = if (fav) primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (current && playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, null,
            Modifier.size(34.dp), tint = primary)
    }
}
