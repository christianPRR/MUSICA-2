package com.musica.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = PowerampScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Text("MUSICA se cerró por un error", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                Text("Toma una captura de esta pantalla o copia el texto y envíamelo.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("error", text))
                    }) { Text("Copiar error") }
                    OutlinedButton(onContinue) { Text("Continuar") }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(text.take(3500), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}
