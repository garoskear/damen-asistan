package com.damen.asistan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NothingScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF101010),
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFFF0000),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFFF0000),
)

class MainActivity : ComponentActivity() {
    private val client by lazy { GwClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AsistanConfig.load(this)
        val extPath = AsistanConfig.ensureExternalTemplate()
        setContent {
            MaterialTheme(colorScheme = NothingScheme) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    var token by remember { mutableStateOf(cfg.token) }
                    var showToken by remember { mutableStateOf(token.isBlank()) }
                    if (showToken) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            Arrangement.Center, Alignment.Start,
                        ) {
                            Text("DAMEN", fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 4.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Gateway token gerekli.\nTermux'ta: cat ~/.damen-gw-token\n(sunucu cwd'sindeki .damen-gw-token)\n\nConfig: $extPath",
                                color = Color.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = token, onValueChange = { token = it },
                                label = { Text("token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { if (token.isNotBlank()) showToken = false },
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

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }
}
