package com.musica.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
fun SettingsScreen(songs: List<Song>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { AudioFx.prefs(ctx) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "hifi") ?: "hifi") }
    var eqOn by remember { mutableStateOf(prefs.getBoolean("eq_on", false)) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(mode) { status = AudioFx.applyMode(ctx); AudioFx.applyEq(ctx) }

    val eq = AudioFx.eq
    val n = eq?.numberOfBands?.toInt() ?: 0
    val minL = eq?.bandLevelRange?.get(0)?.toInt() ?: -1500
    val maxL = eq?.bandLevelRange?.get(1)?.toInt() ?: 1500
    val levels = remember { mutableStateListOf<Int>().apply { for (i in 0 until n) add(prefs.getInt("band$i", 0)) } }
    fun saveBand(i: Int, v: Int) {
        levels[i] = v; prefs.edit().putInt("band$i", v).apply(); AudioFx.applyEq(ctx)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                Text("Ajustes de sonido", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text("Modo de audio", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "hifi", { mode = "hifi"; prefs.edit().putString("mode", "hifi").apply() },
                    { Text("Hi-Fi puro") })
                FilterChip(mode == "surround", { mode = "surround"; prefs.edit().putString("mode", "surround").apply() },
                    { Text("Envolvente") })
            }
            Spacer(Modifier.height(8.dp))
            Text(if (mode == "hifi")
                "Sin efectos ni ecualizador. Reproduce hasta 24 bits con salida en float. Para sonido puro, desactiva Dolby Atmos en los ajustes del sistema."
                else "El audio pasa por el procesamiento del sistema, así que Dolby Atmos y el ecualizador pueden actuar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(status, color = MaterialTheme.colorScheme.primary)
            Text(AudioFx.spatialStatus(ctx), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button({ ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Abrir ajustes de sonido (Dolby Atmos)")
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ecualizador", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(eqOn, { eqOn = it; prefs.edit().putBoolean("eq_on", it).apply(); AudioFx.applyEq(ctx) },
                    enabled = mode == "surround")
            }
            if (mode != "surround") Text("Disponible solo en modo Envolvente.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n == 0) Text("Se activa al conectar el reproductor. Cierra y abre los ajustes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                val on = mode == "surround" && eqOn
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (name, p) ->
                        AssistChip(onClick = {
                            for (i in 0 until n) saveBand(i, p[i * 5 / n].coerceIn(minL, maxL))
                        }, label = { Text(name) }, enabled = on)
                    }
                }
                for (i in 0 until n) {
                    val hz = (eq!!.getCenterFreq(i.toShort()) / 1000)
                    Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz")
                    Slider(levels[i].toFloat(), { saveBand(i, it.toInt()) }, enabled = on,
                        valueRange = minL.toFloat()..maxL.toFloat())
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Graves y volumen (modo Envolvente)", fontWeight = FontWeight.Bold)
            var bass by remember { mutableIntStateOf(prefs.getInt("bass", 0)) }
            Text("Refuerzo de graves: ${bass / 10}%")
            Slider(bass.toFloat(), { bass = it.toInt(); prefs.edit().putInt("bass", bass).apply(); AudioFx.applyFx(ctx) },
                enabled = mode == "surround", valueRange = 0f..1000f)
            var loud by remember { mutableIntStateOf(prefs.getInt("loud", 0)) }
            Text("Loudness (refuerzo de volumen): +${"%.1f".format(loud / 100f)} dB")
            Slider(loud.toFloat(), { loud = it.toInt(); prefs.edit().putInt("loud", loud).apply(); AudioFx.applyFx(ctx) },
                enabled = mode == "surround", valueRange = 0f..1500f)
            Spacer(Modifier.height(16.dp))
            Text("Volumen uniforme (ReplayGain)", fontWeight = FontWeight.Bold)
            var rg by remember { mutableStateOf(prefs.getString("rg", "off") ?: "off") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off" to "Apagado", "track" to "Pista", "album" to "Álbum").forEach { (k, l) ->
                    FilterChip(rg == k, { rg = k; prefs.edit().putString("rg", k).apply() }, { Text(l) })
                }
            }
            Text("Usa las etiquetas ReplayGain del archivo. Si una canción no las tiene, su volumen no cambia.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Fundido entre canciones", fontWeight = FontWeight.Bold)
            var fade by remember { mutableIntStateOf(prefs.getInt("fade_s", 0)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 2, 4, 6).forEach { s ->
                    FilterChip(fade == s, { fade = s; prefs.edit().putInt("fade_s", s).apply() },
                        { Text(if (s == 0) "Apagado" else "$s s") })
                }
            }
            Text("La reproducción sin pausas (gapless) funciona sola en los formatos que lo permiten. El fundido baja y sube el volumen al final y al inicio de cada canción; no las superpone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Text("Metadatos", fontWeight = FontWeight.Bold)
            Text("Descarga las portadas y letras que falten de toda tu biblioteca. Puede tardar y usa internet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val scope = rememberCoroutineScope()
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
            }) { Text(if (running) "Cancelar ($done/${songs.size})" else "Descargar portadas y letras de toda la biblioteca") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
