package com.damen.asistan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ALL_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
private val BtnH = 48.dp

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return ""
    return try { SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(ms)) } catch (_: Exception) { "" }
}

private fun srcTag(s: String) = when (s) {
    "extension" -> "EXT"; "prompt" -> "PROMPT"; "skill" -> "SKILL"; "builtin" -> "SYS"
    else -> s.uppercase().take(4)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(client: GwClient, token: String, onTokenNeeded: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("damen", Context.MODE_PRIVATE) }

    Lang.code = prefs.getString("damen-lang", "tr") ?: "tr"

    val conn by client.conn.collectAsState()
    val messages by client.messages.collectAsState()
    val live by client.live.collectAsState()
    val streaming by client.streaming.collectAsState()
    val sessions by client.sessions.collectAsState()
    val models by client.models.collectAsState()
    val commands by client.commands.collectAsState()
    val notices by client.notices.collectAsState()
    val toastMsg by client.toast.collectAsState()
    val booting by client.booting.collectAsState()
    val dialog by client.dialog.collectAsState()
    val stats by client.stats.collectAsState()
    val qSteer by client.queueSteer.collectAsState()
    val qFollow by client.queueFollow.collectAsState()
    val statuses by client.statuses.collectAsState()
    val widgets by client.widgets.collectAsState()
    client.stateTick.collectAsState()

    var input by remember { mutableStateOf("") }
    var pendingFiles by remember { mutableStateOf(JSONArray()) }
    var modelSheet by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var slashActive by remember { mutableStateOf(0) }
    var stick by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()
    val clipboard = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val history = remember {
        mutableListOf<String>().apply {
            try {
                val a = JSONArray(prefs.getString("damen-history", "[]") ?: "[]")
                for (i in 0 until a.length()) add(a.optString(i))
            } catch (_: Exception) { }
        }
    }
    var histIdx by remember { mutableStateOf(-1) }
    var histDraft by remember { mutableStateOf("") }
    fun historyPush(text: String) {
        if (text.isBlank()) return
        history.remove(text); history.add(0, text)
        while (history.size > 50) history.removeLast()
        try { prefs.edit().putString("damen-history", JSONArray(history).toString()).apply() } catch (_: Exception) { }
        histIdx = -1
    }

    val sessionKey = "damen-draft:${client.sessionFile ?: client.sessionName ?: "default"}"
    // Oturum değişince taslağı sakla/geri koy (web applyState deseni)
    var lastKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionKey) {
        if (lastKey == null) { if (input.isEmpty()) input = prefs.getString(sessionKey, "") ?: "" }
        else if (sessionKey != lastKey) {
            try { prefs.edit().putString(lastKey, input).apply() } catch (_: Exception) { }
            input = prefs.getString(sessionKey, "") ?: ""
        }
        lastKey = sessionKey
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = client.stageUris(ctx.contentResolver, uris)
            val merged = JSONArray()
            for (i in 0 until pendingFiles.length()) merged.put(pendingFiles.get(i))
            for (i in 0 until staged.length()) merged.put(staged.get(i))
            if (merged.length() > 0) client.toast("+ ${staged.length()} dosya")
            pendingFiles = merged
        }
    }

    LaunchedEffect(token) {
        if (token.isBlank()) onTokenNeeded()
        else { client.connect(token); client.listSessions() }
    }

    // Toast otomatik kapatma (hata 6sn, diğer 3sn)
    toastMsg?.let { tm ->
        LaunchedEffect(tm.id) {
            delay(if (tm.level == "error") 6000 else 3000)
            client.clearToast(tm.id)
        }
    }

    // Akan tool kartlarındaki süre hapları (1sn, kart kalmayınca durur)
    val runningTools = live.filterIsInstance<LiveSeg.Tool>().count { it.phase == "running" }
    LaunchedEffect(runningTools) {
        while (runningTools > 0) { delay(1000); tick++ }
    }

    // Yapışkan kaydırma: kullanıcı yukarı kaydırınca bırak, dibe inince tut
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val total = listState.layoutInfo.totalItemsCount
            if (last != null && total > 0) stick = last.index >= total - 1
        }
    }
    LaunchedEffect(messages.size, live, notices.size) {
        if (stick) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) try { listState.scrollToItem(total - 1) } catch (_: Exception) { }
        }
    }

    fun copyLast() {
        val last = messages.lastOrNull { it.role == "assistant" }
        val text = last?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }?.trim() ?: ""
        if (text.isEmpty()) { client.toast(Lang.t("noAssistant"), "warning"); return }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("damen", text))
            client.toast(Lang.t("copied"))
        } catch (_: Exception) { client.toast(Lang.t("errCopy"), "error") }
    }

    fun submit(raw: String) {
        val text = raw.trim()
        if (streaming && text.isEmpty()) { client.abort(); return }
        if (text.isEmpty() && pendingFiles.length() == 0) return
        val m = Regex("""^/([^\s/]+)(?:\s+([\s\S]*))?\s*$""").matchEntire(text)
        if (m != null) {
            val name = m.groupValues[1].lowercase()
            val args = m.groupValues[2].trim()
            historyPush(text)
            input = ""
            if (name == "resume") { scope.launch { drawerState.open() }; client.listSessions(); return }
            if (name == "help") { client.showHelp(); return }
            if (name == "copy") { copyLast(); return }
            if (name == "model" && args.isEmpty()) { client.listModels(); modelQuery = ""; modelSheet = true; return }
        }
        val names = client.pendingNames(pendingFiles)
        val files = pendingFiles
        pendingFiles = JSONArray()
        historyPush(text)
        client.optimisticUser(text, names)
        client.sendPrompt(text, files)
        try { prefs.edit().remove(sessionKey).apply() } catch (_: Exception) { }
        input = ""
        if (streaming) client.toast(Lang.t("queued"))
        stick = true
    }

    // Slash adayları: ^/[^\s]*$ (trailing space → kapalı)
    val slashItems = remember(input, commands) {
        val mm = Regex("""^/([^\s]*)$""").matchEntire(input) ?: return@remember null
        val prefix = mm.groupValues[1].lowercase()
        commands.filter { it.name.lowercase().startsWith(prefix) }.take(20)
    }
    val slashOpen = slashItems != null && slashItems.isNotEmpty()

    BackHandler(enabled = slashOpen) { input = "" }
    BackHandler(enabled = dialog != null) {
        dialog?.let { d -> if (d.id != null) client.dialogResponse(d.id, true) }
        client.hideDialog()
    }
    BackHandler(enabled = modelSheet) { modelSheet = false }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val sessionLabel = client.sessionName
        ?: client.sessionFile?.split('/', '\\')?.lastOrNull()?.removeSuffix(".jsonl") ?: "—"
    val stripVisible = statuses.isNotEmpty() || widgets.isNotEmpty() || stats != null || client.modelId != null
    // Strip scroll durumları ekran seviyesinde: koşul açılıp kapanınca pozisyon sıfırlanmasın.
    val stripVScroll = rememberScrollState()
    val stripHScroll = rememberScrollState()
    // Klavye açıkken navigationBars inset'i yerine ime inset'i kullan (bar klavyenin üstünde kalsın)
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
                drawerContainerColor = Damen.DrawerBg,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Micro(Lang.t("sessions"), Damen.Dim, Modifier.weight(1f))
                    GhostBtn(Lang.t("new")) { scope.launch { drawerState.close() }; client.newSession() }
                    GhostBtn(if (Lang.code == "tr") "TR/EN" else "EN/TR") {
                        Lang.code = if (Lang.code == "tr") "en" else "tr"
                        try { prefs.edit().putString("damen-lang", Lang.code).apply() } catch (_: Exception) { }
                    }
                }
                Divider(color = Damen.LineDim, thickness = 1.dp)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (sessions.isEmpty()) item {
                        Text(Lang.t("noSessions"), fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Faint, modifier = Modifier.padding(12.dp))
                    }
                    items(sessions.size, key = { sessions[it].path }) { k ->
                        val s = sessions[k]
                        val active = s.path == client.sessionFile
                        val title = s.name ?: s.title ?: s.path.split('/', '\\').lastOrNull()?.removeSuffix(".jsonl") ?: "?"
                        val meta = buildString {
                            append(fmtTime(s.modified))
                            if (s.messageCount > 0) { if (isNotEmpty()) append("  ·  "); append("${s.messageCount} ${Lang.t("messages")}") }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { scope.launch { drawerState.close() }; client.switchSession(s.path) }
                                .padding(start = if (active) 10.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                                .then(if (active) Modifier.startBorder(2.dp, color = Damen.Accent) else Modifier),
                        ) {
                            Text(
                                title + if (s.running) " ●" else "",
                                fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Fg,
                                maxLines = 1,
                            )
                            if (meta.isNotEmpty()) Micro(meta, Damen.Faint, Modifier.padding(top = 2.dp))
                        }
                        Divider(color = Damen.LineDim, thickness = 1.dp)
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = Damen.Bg,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Damen.Bg)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp)
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconBtn("☰", Lang.t("sessions")) { client.listSessions(); scope.launch { drawerState.open() } }
                    Text("DAMEN", fontFamily = Damen.Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.8.sp, color = Damen.Fg)
                    Text(
                        sessionLabel, fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.sp,
                        color = Damen.Faint, maxLines = 1, modifier = Modifier.weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { client.listModels(); modelQuery = ""; modelSheet = true },
                    )
                    if (streaming || booting) RecDot()
                }
                Divider(color = Damen.LineDim, thickness = 1.dp)
            },
            bottomBar = {
                Column(
                    modifier = Modifier.background(Damen.Bg)
                        .then(if (imeBottom > 0) Modifier.imePadding() else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
                ) {
                    if (qSteer > 0 || qFollow > 0) {
                        val parts = mutableListOf<String>()
                        if (qSteer > 0) parts += "▲ $qSteer ${Lang.t("steer")}"
                        if (qFollow > 0) parts += "▽ $qFollow ${Lang.t("followUp")}"
                        Text(parts.joinToString("   ·   "), fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Faint, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                        Divider(color = Damen.LineDim, thickness = 1.dp)
                    }
                    if (stripVisible) {
                        Column(
                            modifier = Modifier.heightIn(max = 88.dp)
                                .verticalScroll(stripVScroll)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) {
                            if (statuses.isNotEmpty() || widgets.isNotEmpty()) {
                                Row(modifier = Modifier.horizontalScroll(stripHScroll), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    statuses.forEach { Text(it, fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Dim) }
                                    widgets.forEach { Text(it, fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Dim) }
                                }
                            }
                            StatsLine(client, stats)
                        }
                        Divider(color = Damen.LineDim, thickness = 1.dp)
                    }
                    // Kompozitör
                    Box {
                        if (slashOpen) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 10.dp)
                                    .background(Damen.Surface)
                                    .border(1.dp, Damen.Line),
                            ) {
                                slashItems!!.forEachIndexed { idx, c ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { input = "/${c.name} " }
                                            .padding(start = if (idx == slashActive) 10.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                                            .then(if (idx == slashActive) Modifier.startBorder(2.dp, color = Damen.Accent) else Modifier),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("/" + c.name, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg)
                                        Text(
                                            if (Lang.code == "tr") c.description else c.descriptionEn.ifBlank { c.description },
                                            fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Faint,
                                            maxLines = 1, modifier = Modifier.weight(1f),
                                        )
                                        Micro(srcTag(c.source), Damen.Faint)
                                    }
                                    if (idx < slashItems.lastIndex) Divider(color = Damen.LineDim, thickness = 1.dp)
                                }
                            }
                        }
                    }
                    if (pendingFiles.length() > 0) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val names = client.pendingNames(pendingFiles)
                            ChipRow(names) { idx ->
                                val next = JSONArray()
                                for (i in 0 until pendingFiles.length()) if (i != idx) next.put(pendingFiles.get(i))
                                pendingFiles = next
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp, 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(modifier = Modifier.height(BtnH).width(36.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { picker.launch(arrayOf("*/*")) }, contentAlignment = Alignment.Center) {
                            Text("＋", fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Dim)
                        }
                        TextField(
                            value = input,
                            onValueChange = {
                                input = it
                                slashActive = 0
                                try { prefs.edit().putString(sessionKey, it).apply() } catch (_: Exception) { }
                            },
                            modifier = Modifier.weight(1f)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when {
                                        e.key == Key.Enter && !e.isShiftPressed -> { submit(input); true }
                                        e.key == Key.DirectionUp && slashOpen -> { slashActive = (slashActive - 1 + slashItems!!.size) % slashItems.size; true }
                                        e.key == Key.DirectionDown && slashOpen -> { slashActive = (slashActive + 1) % slashItems!!.size; true }
                                        e.key == Key.Tab && slashOpen -> { input = "/${slashItems!![slashActive].name} "; true }
                                        e.key == Key.DirectionUp && history.isNotEmpty() && !input.contains("\n") -> {
                                            if (histIdx == -1) { histDraft = input; histIdx = 0 } else histIdx = minOf(histIdx + 1, history.lastIndex)
                                            input = history[histIdx]; true
                                        }
                                        e.key == Key.DirectionDown && histIdx >= 0 && !input.contains("\n") -> {
                                            if (histIdx == 0) { histIdx = -1; input = histDraft } else { histIdx--; input = history[histIdx] }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            placeholder = { Text("…", fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Faint) },
                            textStyle = TextStyle(fontFamily = Damen.Mono, fontSize = 14.sp, lineHeight = 20.sp, color = Damen.Fg),
                            maxLines = 5,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        if (streaming && input.trim().isNotEmpty()) {
                            Box(modifier = Modifier.height(BtnH).width(44.dp).border(1.dp, Damen.Line).clickable { submit(input) }, contentAlignment = Alignment.Center) {
                                Text("↑", fontFamily = Damen.Mono, fontSize = 18.sp, color = Damen.Dim)
                            }
                        }
                        val canSend = streaming || input.trim().isNotEmpty() || pendingFiles.length() > 0
                        Box(
                            modifier = Modifier.height(BtnH).width(44.dp)
                                .border(1.dp, if (canSend) Damen.Accent else Damen.Line)
                                .clickable(enabled = canSend) { if (streaming) client.abort() else submit(input) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (streaming) {
                                val inf = rememberInfiniteTransition(label = "send")
                                val a by inf.animateFloat(1f, 0.25f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "send")
                                Box(modifier = Modifier.size(16.dp).alpha(a).border(1.dp, Damen.Fg))
                            } else {
                                Box(modifier = Modifier.size(14.dp).background(if (canSend) Damen.Accent else Damen.Faint))
                            }
                        }
                    }
                }
            },
        ) { pad ->
            Box(modifier = Modifier.fillMaxSize().padding(pad)) {
                if (messages.isEmpty() && live.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        DotGlyph()
                        Spacer(Modifier.height(16.dp))
                        Text(Lang.t("emptyHint"), fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 2.5.sp, color = Damen.Faint)
                    }
                } else {
                    var num = 0
                    var noticeIdx = 0
                    val sortedNotices = notices
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                        messages.forEachIndexed { mi, m ->
                            if (m.role == "user" || m.role == "assistant") {
                                num++
                                val n = num
                                item(key = "m$mi") { Turn(n, m, client, tick) }
                            } else if (m.role == "bashExecution") {
                                item(key = "m$mi") { BashTurn(num + 1, m) }
                            }
                            while (noticeIdx < sortedNotices.size && sortedNotices[noticeIdx].at <= mi + 1) {
                                val nt = sortedNotices[noticeIdx]
                                item(key = "n$noticeIdx") { NoticeRow(nt) }
                                noticeIdx++
                            }
                        }
                        while (noticeIdx < sortedNotices.size) {
                            val nt = sortedNotices[noticeIdx]
                            item(key = "n$noticeIdx") { NoticeRow(nt) }
                            noticeIdx++
                        }
                        if (live.isNotEmpty()) {
                            item(key = "live") {
                                Column(modifier = Modifier.padding(12.dp, 14.dp)) {
                                    live.forEach { seg ->
                                        when (seg) {
                                            is LiveSeg.Thinking -> if (seg.text.isNotEmpty()) ThinkingBlock(seg.text, seg.expanded) { seg.expanded = !seg.expanded; tick++ }
                                            is LiveSeg.Text -> if (seg.text.isNotEmpty()) MdBody(seg.text)
                                            is LiveSeg.Tool -> ToolCard(seg.name, seg.args, seg.argsRaw, seg.output, seg.phase, seg.isError, seg.t0, tick)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                if (!stick && (messages.isNotEmpty() || live.isNotEmpty())) {
                    OutlinedButton(
                        onClick = { stick = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Damen.Line),
                    ) { Text("↓", fontFamily = Damen.Mono, fontSize = 16.sp, color = Damen.Fg) }
                }
                toastMsg?.let { tm ->
                    Box(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                            .background(Damen.Surface2)
                            .border(1.dp, if (tm.level == "error") Damen.Accent else Damen.Line)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(tm.text, fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Fg) }
                }
            }
        }
    }

    if (modelSheet) {
        ModelSheet(
            client = client,
            query = modelQuery,
            onQuery = { modelQuery = it },
            onClose = { modelSheet = false },
        )
    }

    dialog?.let { d -> DialogSheet(d, commands, client) }
}

@Composable
private fun IconBtn(text: String, desc: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(48.dp).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onClick() }, contentAlignment = Alignment.Center) {
        Text(text, fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Dim)
    }
}

@Composable
private fun GhostBtn(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.border(1.dp, Damen.Line).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onClick() }.padding(horizontal = 8.dp, vertical = 5.dp)) {
        Micro(text, Damen.Dim)
    }
}

@Composable
private fun RecDot() {
    val inf = rememberInfiniteTransition(label = "rec")
    val a by inf.animateFloat(1f, 0.25f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "rec")
    Box(modifier = Modifier.padding(end = 4.dp)) {
        Box(modifier = Modifier.size(8.dp).alpha(a).background(Damen.Accent))
    }
}

@Composable
private fun Turn(num: Int, m: ChatMsg, client: GwClient, tick: Int) {
    Column(modifier = Modifier.padding(12.dp, 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(num.toString().padStart(2, '0'), fontFamily = Damen.Mono, fontSize = 10.sp, color = Damen.Faint)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Micro(if (m.role == "user") Lang.t("you") else Lang.t("assistant"), Damen.Dim)
                Box(modifier = Modifier.size(6.dp).background(if (m.role == "user") Damen.Dim else Damen.Accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        m.parts.forEach { p ->
            when (p) {
                is Part.Text -> if (p.text.isNotBlank()) MdBody(p.text)
                is Part.Thinking -> { var ex by remember { mutableStateOf(false) }; ThinkingBlock(p.thinking, ex) { ex = !ex } }
                is Part.ToolCall -> ToolCard(p.name, p.args, p.argsRaw, client.outputFor(p.id), "end", false, 0, tick)
                is Part.Image -> {
                    val ext = p.mime?.split("/")?.getOrNull(1) ?: ""
                    ChipRow(listOf(if (ext.isNotEmpty()) "image.$ext" else "image"))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    Divider(color = Damen.LineDim, thickness = 1.dp)
}

@Composable
private fun BashTurn(num: Int, m: ChatMsg) {
    Column(modifier = Modifier.padding(12.dp, 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(num.toString().padStart(2, '0'), fontFamily = Damen.Mono, fontSize = 10.sp, color = Damen.Faint)
            Micro("BASH", Damen.Dim, Modifier.weight(1f))
        }
        Text(m.command, fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Fg, modifier = Modifier.padding(top = 6.dp))
        Text(
            m.output.takeLast(4000), fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .background(Damen.Surface).border(1.dp, Damen.LineDim).padding(10.dp, 12.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
    Divider(color = Damen.LineDim, thickness = 1.dp)
}

@Composable
private fun ThinkingBlock(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onToggle() }) {
        Text("···", fontFamily = Damen.Mono, fontSize = 12.sp, letterSpacing = 2.8.sp, color = Damen.Faint)
        Text(
            text, fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Dim,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            modifier = Modifier.padding(start = 10.dp).startBorder(1.dp, color = Damen.Line).padding(start = 0.dp),
        )
    }
}

@Composable
private fun NoticeRow(n: Notice) {
    Text(
        n.text, fontFamily = Damen.Mono, fontSize = 11.sp,
        color = when (n.level) { "error" -> Damen.Accent; "warning" -> Damen.Dim; else -> Damen.Faint },
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
    )
}

private fun toolSubtitle(args: Any?): String {
    if (args == null) return ""
    if (args is String) return args.lineSequence().firstOrNull()?.take(120) ?: ""
    if (args is JSONObject) {
        for (k in listOf("path", "command", "pattern", "query", "url")) {
            val v = args.optString(k, "")
            if (v.isNotEmpty()) return v.lineSequence().firstOrNull() ?: ""
        }
        for (k in args.keys()) {
            val v = args.optString(k, "")
            if (v.isNotEmpty()) return v.lineSequence().firstOrNull()?.take(120) ?: ""
        }
    }
    return ""
}

@Composable
private fun ToolCard(
    name: String, args: String, argsRaw: Any?, output: String,
    phase: String, isError: Boolean, t0: Long, tick: Int,
) {
    var open by remember { mutableStateOf(false) }
    val bg = when { isError -> Damen.ErrBg; phase == "running" -> Damen.RunBg; else -> Damen.OkBg }
    Column(modifier = Modifier.background(bg)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = !open }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                phase == "running" -> {
                    val inf = rememberInfiniteTransition(label = "tool")
                    val r by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(700), RepeatMode.Restart), label = "tool")
                    Text("◌", fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Dim)
                }
                isError -> Text("✗", fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Accent)
                else -> Text("✓", fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Ok)
            }
            Text(name, fontFamily = Damen.Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Damen.Fg)
            val sub = toolSubtitle(argsRaw ?: args)
            var subEx by remember { mutableStateOf(false) }
            if (sub.isNotEmpty()) {
                Text(
                    sub, fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Faint,
                    maxLines = if (subEx) Int.MAX_VALUE else 1,
                    modifier = Modifier.weight(1f).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { subEx = !subEx },
                )
            } else Spacer(Modifier.weight(1f))
            if (phase == "running") {
                val sec = if (t0 > 0) maxOf(0, (System.currentTimeMillis() - t0) / 1000) else 0
                tick.let { }
                Box(modifier = Modifier.border(1.dp, Damen.Line).padding(horizontal = 7.dp)) {
                    Text("${Lang.t("running")} ${sec}${Lang.t("secSuffix")}", fontFamily = Damen.Mono, fontSize = 10.sp, color = Damen.Dim)
                }
            }
        }
        val preview = (output.ifNotBlank { output } ?: args).replace(Regex("\\s+"), " ").trim().take(200)
        if (!open && preview.isNotEmpty()) {
            Text(preview, fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Faint, maxLines = 1, modifier = Modifier.padding(start = 32.dp, end = 10.dp, bottom = 6.dp))
        }
        if (open) {
            Column(modifier = Modifier.padding(8.dp, 10.dp)) {
                val editInfo = if (name == "edit") normalizeEdits(argsRaw ?: args) else null
                if (editInfo != null) {
                    val (path, edits) = editInfo
                    if (!path.isNullOrBlank()) {
                        Text(path.split('/', '\\').lastOrNull() ?: path, fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Fg, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    edits.take(10).forEach { (oldT, newT) ->
                        val ops = lineDiff(oldT, newT)
                        if (ops == null) {
                            Text("$oldT\n---\n$newT", fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Dim)
                        } else {
                            ops.take(150).forEach { (t, s) ->
                                Text(
                                    "$t $s",
                                    fontFamily = Damen.Mono, fontSize = 12.sp,
                                    color = when (t) { '−' -> Damen.Dim; '+' -> Damen.Fg; else -> Damen.Faint },
                                    modifier = Modifier.fillMaxWidth()
                                        .background(when (t) { '−' -> Color(0x14D71921); '+' -> Color(0x0DF5F5F5); else -> Color.Transparent })
                                        .startBorder(2.dp, color = when (t) { '−' -> Damen.Accent; '+' -> Damen.Dim; else -> Color.Transparent })
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                } else if (args.isNotEmpty()) {
                    Micro("ARGS", Damen.Faint)
                    Text(args, fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Dim, modifier = Modifier.padding(top = 2.dp))
                }
                if (output.isNotEmpty()) {
                    Micro("OUTPUT", Damen.Faint, Modifier.padding(top = 6.dp))
                    var outEx by remember { mutableStateOf(false) }
                    val shown = if (output.length > 8000) "…" + output.takeLast(8000) else output
                    val long = output.length > 600 || output.count { it == '\n' } > 10
                    Text(
                        shown, fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Dim,
                        maxLines = if (outEx || !long) Int.MAX_VALUE else 8,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (long) {
                        Box(modifier = Modifier.border(1.dp, Damen.Line).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { outEx = !outEx }.padding(horizontal = 10.dp, vertical = 1.dp)) {
                            Text(if (outEx) "▲" else "▼", fontFamily = Damen.Mono, fontSize = 10.sp, color = Damen.Dim)
                        }
                    }
                }
            }
        }
    }
    Divider(color = Damen.LineDim, thickness = 1.dp)
}

private fun String.ifNotBlank(f: () -> String): String = if (isNotBlank()) this else f()

@Composable
private fun StatsLine(client: GwClient, stats: Stats?) {
    val modelTxt = (client.modelName ?: client.modelId ?: "").uppercase()
    val pct = stats?.contextPercent
    val win = stats?.contextWindow ?: client.modelContextWindow
    val cls = if (pct != null && pct >= 80) Damen.Accent else if (pct != null && pct >= 50) Damen.Dim else Damen.Faint
    Row(modifier = Modifier.padding(top = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (modelTxt.isNotEmpty()) Text(modelTxt, fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.1.sp, color = Damen.Faint)
        Text(client.thinkingLevel.uppercase(), fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.1.sp, color = Damen.Faint)
        if (pct != null) Text("CTX %.1f%%%s".format(pct, if (win != null) "/${fmtTokens(win)}" else ""), fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.1.sp, color = cls)
        else if (win != null) Text("CTX /${fmtTokens(win)}", fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.1.sp, color = Damen.Faint)
        if (stats?.cost != null) Text("$%.4f".format(stats.cost), fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.1.sp, color = Damen.Faint)
        if (stats?.totalTokens != null) Text("%.1fk tok".format(stats.totalTokens / 1000.0), fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.1.sp, color = Damen.Faint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(client: GwClient, query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val models by client.models.collectAsState()
    client.stateTick.collectAsState()
    val levels = client.thinkingLevels?.takeIf { it.isNotEmpty() } ?: ALL_LEVELS
    val q = query.trim().lowercase()
    val list = if (q.isEmpty()) models else models.filter { "${it.name} ${it.id} ${it.provider}".lowercase().contains(q) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Damen.Surface,
        contentColor = Damen.Fg,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp, 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Micro(Lang.t("model"), Damen.Dim)
                Box(modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClose() }.padding(8.dp)) {
                    Text("✕", fontFamily = Damen.Mono, fontSize = 14.sp, color = Damen.Dim)
                }
            }
            Divider(color = Damen.LineDim, thickness = 1.dp)
            TextField(
                value = query, onValueChange = onQuery,
                placeholder = { Text(Lang.t("searchModels"), fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Faint) },
                textStyle = TextStyle(fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(8.dp, 14.dp).border(1.dp, Damen.Line),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Damen.Bg, unfocusedContainerColor = Damen.Bg,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(modifier = Modifier.padding(10.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Micro(Lang.t("thinking"), Damen.Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    levels.forEach { lv ->
                        val active = lv == client.thinkingLevel
                        Box(modifier = Modifier.border(1.dp, if (active) Damen.Accent else Damen.Line).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { client.setThinking(lv) }.padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(lv.uppercase(), fontFamily = Damen.Mono, fontSize = 10.sp, letterSpacing = 1.4.sp, color = if (active) Damen.Fg else Damen.Dim)
                        }
                    }
                }
            }
            Divider(color = Damen.LineDim, thickness = 1.dp)
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                if (list.isEmpty()) item {
                    Text(Lang.t("noModels"), fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Faint, modifier = Modifier.padding(14.dp))
                }
                items(list.size, key = { list[it].provider + "/" + list[it].id }) { k ->
                    val m = list[k]
                    val active = client.modelProvider == m.provider && client.modelId == m.id
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { client.setModel(m.provider, m.id); onClose() }
                            .padding(start = if (active) 12.dp else 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp)
                            .then(if (active) Modifier.startBorder(2.dp, color = Damen.Accent) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.name.ifBlank { m.id }, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg, modifier = Modifier.weight(1f))
                        Micro(m.provider, Damen.Faint)
                    }
                    Divider(color = Damen.LineDim, thickness = 1.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogSheet(d: DialogState, commands: List<Cmd>, client: GwClient) {
    var field by remember(d.id, d.kind) { mutableStateOf(d.prefill) }
    ModalBottomSheet(
        onDismissRequest = { if (d.id != null) client.dialogResponse(d.id, true); client.hideDialog() },
        containerColor = Damen.Surface,
        contentColor = Damen.Fg,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (d.kind == "help") {
                Micro(Lang.t("helpTitle"), Damen.Dim)
                Spacer(Modifier.height(12.dp))
                commands.forEach { c ->
                    Row(modifier = Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("/" + c.name, fontFamily = Damen.Mono, fontSize = 12.sp, color = Damen.Fg)
                        Text(
                            if (Lang.code == "tr") c.description else c.descriptionEn.ifBlank { c.description },
                            fontFamily = Damen.Mono, fontSize = 11.sp, color = Damen.Faint, modifier = Modifier.weight(1f),
                        )
                    }
                    Divider(color = Damen.LineDim, thickness = 1.dp)
                }
                Spacer(Modifier.height(8.dp))
                DialogBtn(Lang.t("ok"), primary = true) { client.hideDialog() }
            } else {
                Micro(
                    when (d.kind) {
                        "select" -> Lang.t("select"); "confirm" -> Lang.t("confirmKind")
                        "input" -> Lang.t("input"); "editor" -> Lang.t("editor"); else -> "UI"
                    }, Damen.Accent,
                )
                if (d.title.isNotEmpty()) Text(d.title, fontFamily = Damen.Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Damen.Fg, modifier = Modifier.padding(top = 6.dp))
                when (d.kind) {
                    "select" -> {
                        Spacer(Modifier.height(12.dp))
                        d.options.forEach { opt ->
                            DialogBtn(opt, primary = false) { client.dialogResponse(d.id, false, opt); client.hideDialog() }
                        }
                        DialogBtn(Lang.t("cancel"), primary = false) { client.dialogResponse(d.id, true); client.hideDialog() }
                    }
                    "confirm" -> {
                        if (d.message.isNotEmpty()) Text(d.message, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Dim, modifier = Modifier.padding(vertical = 14.dp))
                        DialogBtn(Lang.t("confirm"), primary = true) { client.dialogResponse(d.id, false, confirmed = true); client.hideDialog() }
                        DialogBtn(Lang.t("cancel"), primary = false) { client.dialogResponse(d.id, true); client.hideDialog() }
                    }
                    else -> {
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = field, onValueChange = { field = it },
                            placeholder = { Text(d.placeholder, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Faint) },
                            textStyle = TextStyle(fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg),
                            singleLine = d.kind != "editor",
                            modifier = Modifier.fillMaxWidth().border(1.dp, Damen.Line)
                                .then(if (d.kind == "editor") Modifier.heightIn(min = 160.dp) else Modifier),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Damen.Bg, unfocusedContainerColor = Damen.Bg,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogBtn(Lang.t("ok"), primary = true) { client.dialogResponse(d.id, false, field); client.hideDialog() }
                        DialogBtn(Lang.t("cancel"), primary = false) { client.dialogResponse(d.id, true); client.hideDialog() }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DialogBtn(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()
        .padding(vertical = 4.dp)
        .border(1.dp, if (primary) Damen.Accent else Damen.Line)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }
        .padding(12.dp)) {
        Text(text, fontFamily = Damen.Mono, fontSize = 13.sp, color = Damen.Fg)
    }
}
