package com.damen.asistan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray

private val NothingRed = Color(0xFFFF0000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(client: GwClient, token: String, onTokenNeeded: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by client.conn.collectAsState()
    val messages by client.messages.collectAsState()
    val liveText by client.liveText.collectAsState()
    val liveThinking by client.liveThinking.collectAsState()
    val streaming by client.streaming.collectAsState()
    val sessions by client.sessions.collectAsState()
    val models by client.models.collectAsState()
    val notice by client.notice.collectAsState()
    val booting by client.booting.collectAsState()
    val modelLabel by client.modelLabel.collectAsState()
    val thinkingLabel by client.thinkingLabel.collectAsState()

    var input by remember { mutableStateOf("") }
    var pendingCount by remember { mutableStateOf(0) }
    var pendingFiles by remember { mutableStateOf(JSONArray()) }
    var modelSheet by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = client.stageUris(ctx.contentResolver, uris)
            val merged = JSONArray()
            for (i in 0 until pendingFiles.length()) merged.put(pendingFiles.get(i))
            for (i in 0 until staged.length()) merged.put(staged.get(i))
            pendingFiles = merged
            pendingCount = merged.length()
        }
    }

    LaunchedEffect(token) {
        if (token.isBlank()) onTokenNeeded()
        else { client.connect(token); client.listSessions() }
    }
    LaunchedEffect(messages.size, liveText) {
        if (messages.isNotEmpty() || liveText.isNotEmpty()) {
            try {
                val idx = if (liveText.isNotEmpty()) messages.size else messages.size - 1
                if (idx >= 0) listState.scrollToItem(idx)
            } catch (_: Exception) { }
        }
    }

    if (modelSheet) {
        AlertDialog(
            onDismissRequest = { modelSheet = false },
            title = { Text("Model", fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text("Düşünme: $thinkingLabel", fontSize = 13.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("off", "low", "medium", "high").forEach { lv ->
                            FilterChip(
                                selected = thinkingLabel == lv,
                                onClick = { client.setThinking(lv) },
                                label = { Text(lv) },
                            )
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(models, key = { it.provider + "/" + it.id }) { m ->
                            TextButton(onClick = { client.setModel(m.provider, m.id); modelSheet = false }) {
                                Text("${m.provider}/${m.id}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            }
                        }
                        if (models.isEmpty()) item {
                            Text("liste boş — yükleniyor olabilir", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { client.listModels() }) { Text("Yenile") }
                    TextButton(onClick = { modelSheet = false }) { Text("Kapat") }
                }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Oturumlar", fontFamily = FontFamily.Monospace)
                    Row {
                        TextButton(onClick = {
                            scope.launch { drawerState.close() }
                            client.newSession()
                        }) { Text("+ Yeni") }
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = "kapat")
                        }
                    }
                }
                Divider()
                sessions.forEach { s ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                (s.name ?: s.path.substringAfterLast("/").removeSuffix(".jsonl")).take(40) +
                                    if (s.running) " ●" else "",
                            )
                        },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            client.switchSession(s.path)
                        },
                    )
                }
                if (sessions.isEmpty()) {
                    Text("oturum yok", modifier = Modifier.padding(16.dp), color = Color.Gray, fontSize = 13.sp)
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { client.listSessions(); scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "oturumlar")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DAMEN", fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 2.sp)
                        Text(
                            when (conn) {
                                is Conn.Open -> if (booting) "başlatılıyor…" else "bağlı"
                                is Conn.Connecting, is Conn.Idle -> "bağlanıyor…"
                                is Conn.Error -> "hata: ${(conn as Conn.Error).text.take(60)}"
                            },
                            fontSize = 11.sp,
                            color = if (conn is Conn.Open) Color(0xFF00FF88) else NothingRed,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    AssistChip(
                        onClick = { client.listModels(); modelSheet = true },
                        label = { Text("$modelLabel · $thinkingLabel", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    )
                }
            },
            bottomBar = {
                Column {
                    if (pendingCount > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("+$pendingCount dosya", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            TextButton(onClick = { pendingFiles = JSONArray(); pendingCount = 0 }) {
                                Text("temizle", color = NothingRed)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, contentDescription = "+ dosya", tint = Color.White)
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("mesaj…") },
                            maxLines = 5,
                        )
                        IconButton(onClick = {
                            if (streaming && input.isBlank()) { client.abort(); return@IconButton }
                            if (input.isBlank() && pendingCount == 0) return@IconButton
                            val text = input.trim()
                            val n = pendingCount
                            client.optimisticUser(text, n)
                            client.sendPrompt(text, pendingFiles)
                            input = ""; pendingFiles = JSONArray(); pendingCount = 0
                        }) {
                            Icon(
                                if (streaming && input.isBlank()) Icons.Default.Close else Icons.Default.Send,
                                contentDescription = "gönder",
                                tint = Color.White,
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Box(modifier = Modifier.fillMaxSize().padding(pad)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { (it.role + it.text.take(16) + it.text.length).hashCode() }) { m ->
                        val mine = m.role == "user"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (mine) Color(0xFF1A1A1A) else Color.Transparent,
                                ),
                                border = BorderStroke(1.dp, if (mine) Color(0xFF333333) else Color(0xFF222222)),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        if (mine) "sen" else m.role,
                                        fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace,
                                    )
                                    if (m.thinking.isNotBlank()) {
                                        Text(m.thinking, fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                    }
                                    Text(m.text, fontSize = 14.sp, color = Color.White)
                                }
                            }
                        }
                    }
                    if (liveThinking.isNotBlank()) item {
                        Text(
                            "düşünüyor: " + liveThinking.take(300),
                            fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (liveText.isNotBlank()) item {
                        Card(
                            border = BorderStroke(1.dp, Color(0xFF222222)),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        ) {
                            Text(liveText, modifier = Modifier.padding(10.dp), fontSize = 14.sp, color = Color.White)
                        }
                    }
                    if (streaming && liveText.isBlank() && liveThinking.isBlank()) item {
                        Text("● yazıyor…", color = NothingRed, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                notice?.let { n ->
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        action = { TextButton(onClick = { client.clearNotice() }) { Text("kapat") } },
                    ) { Text(n) }
                }
            }
        }
    }
}
