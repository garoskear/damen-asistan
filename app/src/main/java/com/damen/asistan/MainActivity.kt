package com.damen.asistan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DamenScheme = darkColorScheme(
    background = Damen.Bg,
    surface = Damen.Bg,
    surfaceVariant = Damen.Surface,
    primary = Damen.Fg,
    onPrimary = Damen.Bg,
    secondary = Damen.Accent,
    onBackground = Damen.Fg,
    onSurface = Damen.Fg,
    error = Damen.Accent,
)

class MainActivity : ComponentActivity() {
    private val client by lazy { GwClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Üstteki siyah boşluk: edge-to-edge + şeffaf barlar, içerik inset'leri yer.
        enableEdgeToEdge()
        try {
            WindowCompat.getInsetsController(window, window.decorView)?.let {
                it.isAppearanceLightStatusBars = false
                it.isAppearanceLightNavigationBars = false
            }
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        } catch (_: Exception) { }
        val cfg = AsistanConfig.load(this)
        val extPath = AsistanConfig.ensureExternalTemplate()
        setContent {
            MaterialTheme(colorScheme = DamenScheme) {
                Surface(Modifier.fillMaxSize(), color = Damen.Bg) {
                    var token by remember { mutableStateOf(cfg.token) }
                    var showToken by remember { mutableStateOf(token.isBlank()) }
                    if (showToken) {
                        Column(
                            Modifier.fillMaxSize()
                                .windowInsetsPadding(WindowInsets.systemBars)
                                .padding(24.dp),
                            Arrangement.Center, Alignment.Start,
                        ) {
                            Text("DAMEN", fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 4.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Gateway token gerekli.\nTermux'ta: cat ~/.damen-gw-token\n\nConfig: $extPath",
                                color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = token, onValueChange = { token = it },
                                label = { Text("token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (token.isNotBlank()) {
                                        AsistanConfig.saveToken(this@MainActivity, token.trim())
                                        token = token.trim()
                                        showToken = false
                                    }
                                },
                                enabled = token.isNotBlank(),
                            ) { Text("Bağlan") }
                        }
                    } else {
                        ChatScreen(client, token) { showToken = true }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        client.setVisible(true)
    }

    override fun onStop() {
        client.setVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }
}
