package com.musica.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val presets = linkedMapOf(
    "Plano" to intArrayOf(0, 0, 0, 0, 0),
    "Graves" to intArrayOf(700, 500, 0, 0, 0),
    "Agudos" to intArrayOf(0, 0, 0, 400, 700),
    "Loudness" to intArrayOf(600, 200, -100, 300, 600),
    "Vocal" to intArrayOf(-300, 0, 400, 300, -100),
    "Rock" to intArrayOf(500, 300, -200, 300, 500),
    "Pop" to intArrayOf(-100, 200, 400, 200, -200),
    "Jazz" to intArrayOf(300, 200, -200, 200, 400),
    "Clásica" to intArrayOf(400, 300, -100, 200, 300),
    "Electrónica" to intArrayOf(600, 300, 0, 200, 500),
    "Hip-Hop" to intArrayOf(700, 500, 100, 200, 300),
    "Metal" to intArrayOf(500, 200, -300, 400, 600),
    "Acústico" to intArrayOf(300, 200, 200, 300, 200),
    "Baile" to intArrayOf(600, 400, 0, 200, 300),
    "Latino" to intArrayOf(400, 300, 100, 200, 400),
    "Reggae" to intArrayOf(300, 0, -200, 300, 400),
    "Cine" to intArrayOf(400, 0, 300, 200, 400),
    "Noche" to intArrayOf(200, 100, 0, -100, -200)
)

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SettingsScreen(songs: List<Song>, folders: List<String>, backupMsg: String,
                   onExport: () -> Unit, onImport: () -> Unit, onReload: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { AudioFx.prefs(ctx) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(prefs.getString("mode", "hifi") ?: "hifi") }
    var eqOn by remember { mutableStateOf(prefs.getBoolean("eq_on", true)) }
    var rg by remember { mutableStateOf(prefs.getString("rg", "off") ?: "off") }
    var viz by remember { mutableIntStateOf(prefs.getInt("viz", 0)) }
    var trans by remember {
        mutableIntStateOf(if (prefs.getBoolean("xfade_on", false)) 2 else if (prefs.getInt("fade_s", 0) > 0) 1 else 0)
    }
    var secs by remember {
        mutableIntStateOf(when {
            prefs.getBoolean("xfade_on", false) -> prefs.getInt("xfade_s", 6)
            prefs.getInt("fade_s", 0) > 0 -> prefs.getInt("fade_s", 4)
            else -> 6
        }.coerceIn(1, 12))
    }
    var status by remember { mutableStateOf("") }
    var eqActive by remember { mutableStateOf(false) }
    var sysEqMsg by remember { mutableStateOf("") }
    var altList by remember { mutableStateOf(emptyList<String>()) }
    val fxv = AudioFx.fxVersion
    LaunchedEffect(mode, eqOn, rg, viz, trans) {
        status = AudioFx.applyMode(ctx); AudioFx.applyEq(ctx)
        eqActive = AudioFx.eq?.enabled == true
        altList = AudioFx.signalAlterations(ctx)
    }

    fun applyTrans(t: Int, s: Int) {
        trans = t; secs = s
        val e = prefs.edit()
        when (t) {
            0 -> { e.putBoolean("xfade_on", false); e.putInt("fade_s", 0) }
            1 -> { e.putBoolean("xfade_on", false); e.putInt("fade_s", s) }
            else -> { e.putBoolean("xfade_on", true); e.putInt("fade_s", 0); e.putInt("xfade_s", s) }
        }
        e.apply()
    }
    var pendingViz by remember { mutableIntStateOf(0) }
    val vizPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { viz = pendingViz; prefs.edit().putInt("viz", viz).apply() }
    }
    fun chooseViz(v: Int) {
        if (v == 0 || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viz = v; prefs.edit().putInt("viz", v).apply()
        } else { pendingViz = v; vizPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    val eq = AudioFx.eq
    val n = eq?.numberOfBands?.toInt() ?: 0
    val minL = eq?.bandLevelRange?.get(0)?.toInt() ?: -1500
    val maxL = eq?.bandLevelRange?.get(1)?.toInt() ?: 1500
    val levels = remember(n) { mutableStateListOf<Int>().apply { for (i in 0 until n) add(prefs.getInt("band$i", 0)) } }
    fun saveBand(i: Int, v: Int) {
        levels[i] = v; prefs.edit().putInt("band$i", v).apply(); AudioFx.applyEq(ctx)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
            }

            Section("Apariencia")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!ThemeState.amoled, { ThemeState.setAmoled(ctx, false) }, { Text("Oscuro premium") })
                FilterChip(ThemeState.amoled, { ThemeState.setAmoled(ctx, true) }, { Text("Negro AMOLED") })
            }
            Spacer(Modifier.height(12.dp))
            Text("Color de acento", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FilterChip(ThemeState.accentKey == "dynamic", { ThemeState.setAccent(ctx, "dynamic") }, { Text("Según la portada") })
                AccentOptions.forEach { a ->
                    val sel = ThemeState.accentKey == a.key
                    Box(Modifier.size(36.dp).clip(CircleShape).background(a.color)
                        .then(if (sel) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { ThemeState.setAccent(ctx, a.key) })
                }
            }
            Hint("«Según la portada» toma el color dominante de la canción que suena. Los demás son acentos fijos; el naranja sigue disponible.")

            Section("Calidad de audio")
            Text("Modo de audio", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "hifi", { mode = "hifi"; prefs.edit().putString("mode", "hifi").apply() }, { Text("Hi-Fi puro") })
                FilterChip(mode == "surround", { mode = "surround"; prefs.edit().putString("mode", "surround").apply() }, { Text("Envolvente") })
            }
            Spacer(Modifier.height(8.dp))
            Hint(if (mode == "hifi")
                "Hi-Fi: la app no usa ecualizador ni efectos; la señal pasa directo a Android. FLAC, MP3, M4A y OGG se reproducen con los decodificadores del sistema, con FFmpeg de respaldo."
                else "Envolvente: la señal pasa por el procesamiento del sistema, así que Dolby Atmos y el ecualizador pueden actuar.")
            Spacer(Modifier.height(8.dp))
            Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            if (altList.isNotEmpty()) Hint("Funciones activas que modifican la señal: ${altList.joinToString(", ")}.")
            Spacer(Modifier.height(4.dp))
            Hint(AudioFx.spatialStatus(ctx))
            Hint(AudioFx.conflictNote(ctx))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Sonido del sistema (Atmos)")
                }
                OutlinedButton({ sysEqMsg = if (AudioFx.openSystemEq(ctx)) "" else "Tu teléfono no tiene un ecualizador del sistema." }) {
                    Text("Ecualizador del sistema")
                }
            }
            if (sysEqMsg.isNotEmpty()) Hint(sysEqMsg)

            Section("Ecualizador de la app")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (eqActive) "● Activo" else "○ Inactivo", Modifier.weight(1f),
                    color = if (eqActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(eqOn, { eqOn = it; prefs.edit().putBoolean("eq_on", it).apply(); AudioFx.applyEq(ctx) }, enabled = mode == "surround")
            }
            if (mode != "surround") Hint("Disponible solo en modo Envolvente, para no competir con los efectos del sistema.")
            if (mode == "surround" && n == 0) Hint("Se activa al conectar el reproductor. Cierra y abre los ajustes.")
            if (n > 0) {
                val on = mode == "surround" && eqOn
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (name, p) ->
                        AssistChip(onClick = { for (i in 0 until n) saveBand(i, p[i * 5 / n].coerceIn(minL, maxL)) },
                            label = { Text(name) }, enabled = on)
                    }
                }
                for (i in 0 until n) {
                    val hz = (eq!!.getCenterFreq(i.toShort()) / 1000)
                    Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", fontSize = 13.sp)
                    Slider(levels[i].toFloat(), { saveBand(i, it.toInt()) }, enabled = on,
                        valueRange = minL.toFloat()..maxL.toFloat())
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Graves y volumen (modo Envolvente)", fontWeight = FontWeight.SemiBold)
            var bass by remember { mutableIntStateOf(prefs.getInt("bass", 0)) }
            Text("Refuerzo de graves: ${bass / 10}%", fontSize = 13.sp)
            Slider(bass.toFloat(), { bass = it.toInt(); prefs.edit().putInt("bass", bass).apply(); AudioFx.applyFx(ctx) },
                enabled = mode == "surround", valueRange = 0f..1000f)
            var loud by remember { mutableIntStateOf(prefs.getInt("loud", 0)) }
            Text("Loudness (refuerzo de volumen): +${"%.1f".format(loud / 100f)} dB", fontSize = 13.sp)
            Slider(loud.toFloat(), { loud = it.toInt(); prefs.edit().putInt("loud", loud).apply(); AudioFx.applyFx(ctx) },
                enabled = mode == "surround", valueRange = 0f..1500f)

            Section("Volumen uniforme (ReplayGain)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "Apagado", "track" to "Pista", "album" to "Álbum").forEach { (k, l) ->
                    FilterChip(rg == k, { rg = k; prefs.edit().putString("rg", k).apply() }, { Text(l) })
                }
            }
            Hint("Usa las etiquetas ReplayGain del archivo. Si una canción no las tiene, su volumen no cambia. Modifica el volumen, así que no es compatible con una salida bit-perfect.")

            Section("Transición entre canciones")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(trans == 0, { applyTrans(0, secs) }, { Text("Sin pausas") })
                FilterChip(trans == 1, { applyTrans(1, minOf(secs, 6)) }, { Text("Fundido") })
                FilterChip(trans == 2, { applyTrans(2, secs) }, { Text("Crossfade") })
            }
            if (trans != 0) {
                Text("Duración: $secs s", fontSize = 13.sp)
                Slider(secs.toFloat(), { applyTrans(trans, it.roundToInt().coerceIn(1, if (trans == 1) 6 else 12)) },
                    valueRange = 1f..(if (trans == 1) 6f else 12f), steps = if (trans == 1) 4 else 10)
            }
            Hint(when (trans) {
                2 -> "Crossfade: el final de una canción se mezcla con el inicio de la siguiente. No se aplica a una canción en repetición ni a la última de la lista."
                1 -> "Fundido: baja el volumen al final y lo sube al inicio de cada canción, sin superponerlas."
                else -> "Sin pausas: las canciones no se superponen. La reproducción gapless funciona sola en los formatos que lo permiten."
            })

            Section("Visualizador")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Apagado", "Barras", "Onda").forEachIndexed { i, t -> FilterChip(viz == i, { chooseViz(i) }, { Text(t) }) }
            }
            Hint("Se dibuja solo con el reproductor abierto y la música sonando. Android pide el permiso «Grabar audio» para analizar lo que reproduce la app; no graba ni guarda audio. Al estar activo cuenta como procesamiento.")

            Section("Metadatos")
            Hint("Descarga las portadas y letras que falten de toda tu biblioteca. Puede tardar y usa internet.")
            var running by remember { mutableStateOf(false) }
            var done by remember { mutableIntStateOf(0) }
            var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            Button({
                if (running) job?.cancel()
                else job = scope.launch {
                    running = true; done = 0
                    try {
                        for (s in songs) {
                            if (Repo.loadCover(ctx, s) == null) Repo.downloadCover(ctx, s)
                            Repo.loadLyrics(ctx, s, false)
                            done++; delay(250)
                        }
                    } finally { running = false }
                }
            }) { Text(if (running) "Cancelar ($done/${songs.size})" else "Descargar portadas y letras") }
            Spacer(Modifier.height(16.dp))
            Text("Actualizar etiquetas de los archivos", fontWeight = FontWeight.SemiBold)
            Hint("Busca artista, álbum, género, año, portada y letra en internet y los escribe dentro de cada archivo, para que otras apps también los vean. La portada incrustada funciona en MP3 y M4A.")
            var access by remember { mutableStateOf(Tags.hasAccess()) }
            if (!access) {
                Text("Necesita el permiso de acceso a todos los archivos.", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ ctx.startActivity(Tags.accessIntent(ctx)) }) { Text("Dar permiso") }
                    OutlinedButton({ access = Tags.hasAccess() }) { Text("Ya lo di") }
                }
            }
            var overwrite by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(overwrite, { overwrite = it }); Spacer(Modifier.width(8.dp))
                Text("Sobrescribir datos que ya existen")
            }
            var confirm by remember { mutableStateOf(false) }
            var tagRunning by remember { mutableStateOf(false) }
            var tagDone by remember { mutableIntStateOf(0) }
            var tagOk by remember { mutableIntStateOf(0) }
            var tagJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Modificar archivos") },
                text = { Text("Esto edita los archivos de tu música. Prueba primero con una canción (menú ⋮ del reproductor › Editar etiquetas). Se procesan unas 20 por minuto. ¿Continuar?") },
                confirmButton = { TextButton({
                    confirm = false
                    tagJob = scope.launch {
                        tagRunning = true; tagDone = 0; tagOk = 0
                        try {
                            for (s in songs) {
                                if (Repo.autoUpdate(ctx, s, overwrite)) tagOk++
                                tagDone++; delay(3000)
                            }
                        } finally { tagRunning = false; onReload() }
                    }
                }) { Text("Continuar") } },
                dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
            Button(enabled = access, onClick = { if (tagRunning) tagJob?.cancel() else confirm = true }) {
                Text(if (tagRunning) "Cancelar ($tagDone/${songs.size} · $tagOk actualizadas)" else "Actualizar etiquetas de toda la biblioteca")
            }

            Section("Respaldo y restauración")
            Hint("Guarda en un archivo tus favoritas, listas de reproducción, ajustes, tema e historial de escucha. Elige dónde guardarlo (Drive, Descargas…). Tras reinstalar, abre este menú y restaura el archivo; las canciones se reconocen por título, artista y ruta. Las portadas y letras descargadas no se incluyen: se vuelven a descargar solas. Para exportar una lista suelta (M3U), ábrela en Biblioteca › Listas.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onExport) { Text("Guardar copia") }
                OutlinedButton(onImport) { Text("Restaurar copia") }
            }
            if (backupMsg.isNotEmpty()) Text(backupMsg, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            var clearAsk by remember { mutableStateOf(false) }
            TextButton({ clearAsk = true }) { Text("Borrar historial de escucha") }
            if (clearAsk) AlertDialog(onDismissRequest = { clearAsk = false }, title = { Text("Borrar historial") },
                text = { Text("Se borrarán las estadísticas y el historial de reproducción de este teléfono. ¿Continuar?") },
                confirmButton = { TextButton({ Stats.clear(ctx); clearAsk = false }) { Text("Borrar") } },
                dismissButton = { TextButton({ clearAsk = false }) { Text("Cancelar") } })

            Section("Avanzado")
            Hint("Cierra la app desde recientes y ábrela para aplicar estos cambios.")
            var floatOut by remember { mutableStateOf(prefs.getBoolean("float_out", false)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(floatOut, { floatOut = it; prefs.edit().putBoolean("float_out", it).apply() })
                Spacer(Modifier.width(8.dp))
                Text("Salida float de 32 bits (puede distorsionar en algunos teléfonos)", fontSize = 14.sp)
            }
            var ffmpeg by remember { mutableStateOf(prefs.getBoolean("ffmpeg", true)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(ffmpeg, { ffmpeg = it; prefs.edit().putBoolean("ffmpeg", it).apply() })
                Spacer(Modifier.width(8.dp))
                Text("Decodificadores FFmpeg de respaldo", fontSize = 14.sp)
            }

            Section("Carpetas")
            var min30 by remember { mutableStateOf(prefs.getBoolean("min30", false)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(min30, { min30 = it; prefs.edit().putBoolean("min30", it).apply(); onReload() })
                Spacer(Modifier.width(8.dp))
                Text("Ocultar audios de menos de 30 s (notas de voz, tonos)", fontSize = 14.sp)
            }
            var hidden by remember { mutableStateOf<Set<String>>(prefs.getStringSet("hidden_dirs", emptySet()) ?: emptySet()) }
            folders.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(d !in hidden, { on ->
                        hidden = if (on) hidden - d else hidden + d
                        prefs.edit().putStringSet("hidden_dirs", hidden.toSet()).apply(); onReload()
                    })
                    Spacer(Modifier.width(8.dp))
                    Text(d.substringAfter("/storage/emulated/0/"), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
