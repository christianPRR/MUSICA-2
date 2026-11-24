package com.musica.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Relleno inferior para las listas: la barra flotante de reproducción queda encima del contenido. */
val LocalBottomPad = compositionLocalOf { 0.dp }

class AccentOption(val key: String, val label: String, val color: Color)

val AccentOptions = listOf(
    AccentOption("orange", "Naranja", Color(0xFFFF9800)),
    AccentOption("red", "Rojo", Color(0xFFFF453A)),
    AccentOption("pink", "Rosa", Color(0xFFFF5C93)),
    AccentOption("purple", "Violeta", Color(0xFFB08CFF)),
    AccentOption("blue", "Azul", Color(0xFF4DA3FF)),
    AccentOption("mint", "Menta", Color(0xFF4FE3C1)),
    AccentOption("green", "Verde", Color(0xFF3DDC84))
)

class CoverColors(val accent: Color, val tint: Color)

/** Ajustes de apariencia compartidos por toda la app. */
object ThemeState {
    var amoled by mutableStateOf(false)
    /** "dynamic" = color según la portada; si no, la clave de un acento fijo. */
    var accentKey by mutableStateOf("dynamic")
    var cover by mutableStateOf<CoverColors?>(null)

    fun load(ctx: Context) {
        val p = AudioFx.prefs(ctx)
        amoled = p.getString("theme", "premium") == "amoled"
        accentKey = p.getString("accent", "dynamic") ?: "dynamic"
    }

    fun setAmoled(ctx: Context, v: Boolean) {
        amoled = v
        AudioFx.prefs(ctx).edit().putString("theme", if (v) "amoled" else "premium").apply()
    }

    fun setAccent(ctx: Context, key: String) {
        accentKey = key
        AudioFx.prefs(ctx).edit().putString("accent", key).apply()
    }

    fun accent(): Color {
        val k = accentKey
        if (k == "dynamic") return cover?.accent ?: AccentOptions[0].color
        return AccentOptions.firstOrNull { it.key == k }?.color ?: AccentOptions[0].color
    }

    /** Color de fondo del reproductor: viene de la portada cuando existe. */
    fun tint(): Color = cover?.tint ?: Color(0xFF26262E)
}

/** Esquema base (también lo usa la pantalla de errores). */
val PowerampScheme = darkColorScheme(
    primary = Color(0xFFFF9800), onPrimary = Color.Black,
    background = Color(0xFF0B0B0E), onBackground = Color.White,
    surface = Color(0xFF0B0B0E), onSurface = Color.White,
    surfaceVariant = Color(0xFF26262D), onSurfaceVariant = Color(0xFFA5A5AD),
    surfaceContainer = Color(0xFF1B1B21), primaryContainer = Color(0xFF3A2A10), onPrimaryContainer = Color(0xFFFFCC80)
)

private val MusicaTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

@Composable
fun MusicaTheme(content: @Composable () -> Unit) {
    val amoled = ThemeState.amoled
    val acc = ThemeState.accent()
    val spec = tween<Color>(600)
    val primary by animateColorAsState(acc, spec, label = "accent")
    val bg by animateColorAsState(if (amoled) Color.Black else Color(0xFF0B0B0E), spec, label = "bg")
    val surf by animateColorAsState(if (amoled) Color(0xFF0A0A0A) else Color(0xFF141418), spec, label = "surf")
    val cont by animateColorAsState(if (amoled) Color(0xFF111111) else Color(0xFF1B1B21), spec, label = "cont")
    val variant by animateColorAsState(if (amoled) Color(0xFF1C1C1C) else Color(0xFF26262D), spec, label = "variant")
    val scheme = darkColorScheme(
        primary = primary, onPrimary = Color.Black,
        primaryContainer = primary.copy(alpha = 0.25f).compositeOver(bg), onPrimaryContainer = Color.White,
        secondary = primary, onSecondary = Color.Black,
        background = bg, onBackground = Color.White,
        surface = surf, onSurface = Color.White,
        surfaceVariant = variant, onSurfaceVariant = Color(0xFFA5A5AD),
        surfaceContainer = cont,
        outline = Color(0xFF3A3A42)
    )
    MaterialTheme(colorScheme = scheme, typography = MusicaTypography, content = content)
}

/** Superficie translúcida con borde fino (efecto cristal sobre fondos oscuros). */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), alpha: Float = 0.10f): Modifier =
    this.clip(shape)
        .background(Color.Black.copy(alpha = 0.30f))
        .background(Color.White.copy(alpha = alpha))
        .border(1.dp, Color.White.copy(alpha = 0.14f), shape)

object Palette {
    /** Color dominante vivo de una portada, más una versión oscura para fondos. */
    fun extract(b: Bitmap): CoverColors? = try {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        val hsv = FloatArray(3)
        val w = FloatArray(12); val sumH = FloatArray(12); val sumS = FloatArray(12); val sumV = FloatArray(12)
        var total = 0f
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val c = s.getPixel(x, y)
            if ((c ushr 24) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.15f) continue
            val weight = hsv[1] * hsv[1] * (0.4f + hsv[2])
            if (weight <= 0.001f) continue
            val k = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            w[k] += weight; sumH[k] += hsv[0] * weight; sumS[k] += hsv[1] * weight; sumV[k] += hsv[2] * weight
            total += weight
        }
        if (s !== b) s.recycle()
        if (total < 0.5f) {
            CoverColors(Color(0xFFD0D0D8), Color(0xFF2A2A30))
        } else {
            var best = 0
            for (i in 1 until 12) if (w[i] > w[best]) best = i
            val h = sumH[best] / w[best]
            val sat = (sumS[best] / w[best]).coerceIn(0.45f, 0.9f)
            val v = (sumV[best] / w[best]).coerceIn(0.85f, 1f)
            CoverColors(Color.hsv(h, sat, v), Color.hsv(h, sat.coerceAtMost(0.8f), 0.30f))
        }
    } catch (e: Exception) { null }

    /** Versión diminuta de la portada: al escalarla se ve como un desenfoque suave y casi no cuesta recursos. */
    fun soft(b: Bitmap): Bitmap = try { Bitmap.createScaledBitmap(b, 40, 40, true) } catch (e: Exception) { b }
}
