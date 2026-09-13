package com.damen.asistan

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** damen-gateway WS protokolünün Kotlin karşılığı (web/app.js ile birebir). */

@Immutable
sealed interface Part {
    data class Text(val text: String) : Part
    data class Thinking(val thinking: String) : Part
    data class ToolCall(
        val id: String?,
        val name: String,
        val args: String,
        val argsRaw: Any?,
        val output: String = "",
    ) : Part
    data class Image(val mime: String?) : Part
}

@Immutable
data class ChatMsg(
    val role: String,
    val parts: List<Part>,
    val command: String = "",
    val output: String = "",
)

@Immutable
data class SessionInfo(
    val path: String,
    val name: String?,
    val title: String?,
    val modified: Long,
    val messageCount: Int,
    val running: Boolean = false,
)

@Immutable
data class ModelInfo(val provider: String, val id: String, val name: String)

@Immutable
data class Cmd(
    val name: String,
    val description: String,
    val descriptionEn: String,
    val source: String,
)

@Immutable
data class Stats(
    val totalTokens: Long?,
    val cost: Double?,
    val contextPercent: Double?,
    val contextWindow: Long?,
)

@Immutable
data class Notice(val text: String, val level: String, val at: Int)

@Immutable
data class ToastMsg(val text: String, val level: String, val id: Long)

@Immutable
data class DialogState(
    val id: Int?,
    val kind: String,
    val title: String,
    val message: String,
    val options: List<String>,
    val placeholder: String,
    val prefill: String,
    val commands: List<Cmd> = emptyList(),
)

@Immutable
sealed interface LiveSeg {
    data class Text(val text: String, var expanded: Boolean = false) : LiveSeg
    data class Thinking(val text: String, var expanded: Boolean = false) : LiveSeg
    data class Tool(
        val id: String?,
        var name: String,
        var args: String,
        var argsRaw: Any?,
        var output: String,
        var phase: String,
        var isError: Boolean,
        val t0: Long = System.currentTimeMillis(),
    ) : LiveSeg
}

@Immutable
sealed interface Conn {
    data object Idle : Conn
    data object Connecting : Conn
    data object Open : Conn
    data class Error(val text: String) : Conn
}

val BUILTIN_COMMANDS = listOf(
    Cmd("new", "Yeni oturum başlat", "Start a new session", "builtin"),
    Cmd("resume", "Kayıtlı oturumları aç", "Open saved sessions", "builtin"),
    Cmd("model", "Model seç veya değiştir: /model [provider/id]", "Pick or switch model: /model [provider/id]", "builtin"),
    Cmd("thinking", "Düşünme seviyesi: off|minimal|low|medium|high|xhigh|max", "Thinking level: off|minimal|low|medium|high|xhigh|max", "builtin"),
    Cmd("compact", "Bağlamı sıkıştır: /compact [odak]", "Compact context: /compact [instructions]", "builtin"),
    Cmd("name", "Oturumu adlandır: /name <ad>", "Name the session: /name <name>", "builtin"),
    Cmd("clear-queue", "Bekleyen kuyruğu temizle", "Clear pending steer/follow-up queue", "builtin"),
    Cmd("copy", "Son pi yanıtını panoya kopyala", "Copy last pi reply to clipboard", "builtin"),
    Cmd("help", "Tüm slash komutlarını listele", "List all slash commands", "builtin"),
)

/** Güvenli oturum adı fallback'i: literal 'null' stringini kesinlikle engeller. */
fun cleanSessionTitle(name: String?, title: String?, path: String?, fallback: String = "oturum"): String {
    val n = name?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    if (n != null) return n
    val t = title?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    if (t != null) return t
    val f = path?.split('/', '\\')?.lastOrNull()?.removeSuffix(".jsonl")?.trim()
        ?.takeIf { it.isNotEmpty() && it != "null" }
    if (f != null) return f
    return fallback
}

/** Sunucuyla birebir temizlik (web sanitizeName): iyimser @satırlar settled ile eşleşir. */
fun sanitizeName(name: String): String {
    return name.replace(Regex("\\s+"), "_")
        .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .takeLast(80).ifBlank { "dosya" }
}

fun summarizeArgs(args: Any?): String {
    if (args == null) return ""
    if (args is String) return args
    return try {
        val s = when (args) {
            is JSONObject -> args.toString(1)
            is JSONArray -> args.toString(1)
            else -> args.toString()
        }
        if (s.length > 2000) s.take(2000) + "…" else s
    } catch (_: Exception) { args.toString() }
}

/** Edit argümanından diff bilgisi (web normalizeEdits). */
fun normalizeEdits(raw: Any?): Pair<String?, List<Pair<String, String>>>? {
    val o: JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> try { JSONObject(raw) } catch (_: Exception) { return null }
        else -> return null
    }
    val path = o.safeNull("path")
    val arr = o.optJSONArray("edits")
    if (arr != null) {
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.has("oldText") && e.has("newText")) list += e.optString("oldText") to e.optString("newText")
        }
        return path to list
    }
    if (o.has("oldText") && o.has("newText")) return path to listOf(o.optString("oldText") to o.optString("newText"))
    return null
}

/** Satır diff'i LCS (web lineDiff). Büyük blokta null → ham metin. */
fun lineDiff(a: String, b: String): List<Pair<Char, String>>? {
    val A = a.split("\n"); val B = b.split("\n")
    if (A.size > 500 || B.size > 500) return null
    val n = A.size; val m = B.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
        dp[i][j] = if (A[i] == B[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    val ops = mutableListOf<Pair<Char, String>>()
    var i = 0; var j = 0
    while (i < n && j < m) {
        if (A[i] == B[j]) { ops += Pair(' ', A[i]); i++; j++ }
        else if (dp[i + 1][j] >= dp[i][j + 1]) { ops += Pair('−', A[i]); i++ }
        else { ops += Pair('+', B[j]); j++ }
    }
    while (i < n) { ops += Pair('−', A[i]); i++ }
    while (j < m) { ops += Pair('+', B[j]); j++ }
    return ops
}

class GwClient(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var reconnectDelay = 800L
    private var reconnecting = false

    private val _conn = MutableStateFlow<Conn>(Conn.Idle); val conn: StateFlow<Conn> = _conn
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList()); val messages: StateFlow<List<ChatMsg>> = _messages
    private val _live = MutableStateFlow<List<LiveSeg>>(emptyList()); val live: StateFlow<List<LiveSeg>> = _live
    private val _streaming = MutableStateFlow(false); val streaming: StateFlow<Boolean> = _streaming
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList()); val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList()); val models: StateFlow<List<ModelInfo>> = _models
    private val _commands = MutableStateFlow<List<Cmd>>(BUILTIN_COMMANDS); val commands: StateFlow<List<Cmd>> = _commands
    private val _notices = MutableStateFlow<List<Notice>>(emptyList()); val notices: StateFlow<List<Notice>> = _notices
    private val _toast = MutableStateFlow<ToastMsg?>(null); val toast: StateFlow<ToastMsg?> = _toast
    private val _booting = MutableStateFlow(false); val booting: StateFlow<Boolean> = _booting
    private val _dialog = MutableStateFlow<DialogState?>(null); val dialog: StateFlow<DialogState?> = _dialog
    private val _stats = MutableStateFlow<Stats?>(null); val stats: StateFlow<Stats?> = _stats
    private val _queueSteer = MutableStateFlow(0); val queueSteer: StateFlow<Int> = _queueSteer
    private val _queueFollow = MutableStateFlow(0); val queueFollow: StateFlow<Int> = _queueFollow
    private val _statuses = MutableStateFlow<List<String>>(emptyList()); val statuses: StateFlow<List<String>> = _statuses
    private val _widgets = MutableStateFlow<List<String>>(emptyList()); val widgets: StateFlow<List<String>> = _widgets

    var viewSlot: Int? = null
        private set
    var token: String = ""
    var sessionName: String? = null
        private set
    var sessionFile: String? = null
        private set
    var modelProvider: String? = null
        private set
    var modelId: String? = null
        private set
    var modelName: String? = null
        private set
    var modelContextWindow: Long? = null
        private set
    var thinkingLevel: String = "off"
        private set
    var thinkingLevels: List<String>? = null
        private set
    var attachDir: String? = null
        private set

    private val toolResults = mutableMapOf<String, String>()
    private var toastSeq = 0L
    private val _stateTick = MutableStateFlow(0); val stateTick: StateFlow<Int> = _stateTick

    // Canlı akış: deltada StateFlow'a yazılmaz; çalışma listesi 120ms'de bir flush'lanır.
    private val liveWorking = mutableListOf<LiveSeg>()
    private var liveDirty = false

    init {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(120)
                if (liveDirty) { liveDirty = false; _live.value = liveWorking.toList() }
            }
        }
    }

    fun showHelp() { _dialog.value = DialogState(null, "help", "", "", emptyList(), "", "", _commands.value) }
    fun hideDialog() { _dialog.value = null }

    fun toast(text: String, level: String = "info") { _toast.value = ToastMsg(text, level, ++toastSeq) }
    fun clearToast(id: Long) { if (_toast.value?.id == id) _toast.value = null }

    fun connect(token: String) {
        this.token = token
        disconnect()
        reconnecting = true
        doConnect()
    }

    private fun doConnect() {
        _conn.value = Conn.Connecting
        val req = Request.Builder().url(AsistanConfig.wsUrl(token)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelay = 800
                _conn.value = Conn.Open
                sendRaw(JSONObject().put("type", "visibility").put("visible", true).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { onJson(text) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _conn.value = Conn.Error(t.message ?: "bağlantı hatası")
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _conn.value = Conn.Error("kapandı: $reason")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!reconnecting) return
        scope.launch {
            kotlinx.coroutines.delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, 8000)
            doConnect()
        }
    }

    fun disconnect() {
        reconnecting = false
        try { ws?.close(1000, "bye") } catch (_: Exception) { }
        ws = null
    }

    fun setVisible(v: Boolean) = sendRaw(JSONObject().put("type", "visibility").put("visible", v).toString())

    private fun sendRaw(s: String) { try { ws?.send(s) } catch (_: Exception) { } }

    fun sendPrompt(text: String, files: JSONArray = JSONArray(), slot: Int? = viewSlot) {
        val o = JSONObject().put("type", "prompt").put("text", text).put("files", files)
        if (slot != null) o.put("slot", slot)
        sendRaw(o.toString())
    }
    fun abort() { val o = JSONObject().put("type", "abort"); viewSlot?.let { o.put("slot", it) }; sendRaw(o.toString()) }
    fun newSession() = sendRaw(JSONObject().put("type", "new_session").toString())
    fun listSessions() = sendRaw(JSONObject().put("type", "list_sessions").toString())
    fun switchSession(path: String) {
        if (path.startsWith("slot:")) viewSlot = path.removePrefix("slot:").toIntOrNull()
        sendRaw(JSONObject().put("type", "switch_session").put("path", path).toString())
    }
    fun listModels() = sendRaw(JSONObject().put("type", "list_models").toString())
    fun setModel(provider: String, modelId: String) {
        val o = JSONObject().put("type", "set_model").put("provider", provider).put("modelId", modelId)
        viewSlot?.let { o.put("slot", it) }; sendRaw(o.toString())
    }
    fun setThinking(level: String) {
        val o = JSONObject().put("type", "set_thinking").put("level", level)
        viewSlot?.let { o.put("slot", it) }; sendRaw(o.toString())
    }
    fun dialogResponse(id: Int?, cancelled: Boolean, value: Any? = null, confirmed: Boolean? = null) {
        val o = JSONObject().put("type", "dialog_response")
        if (id != null) o.put("id", id)
        o.put("cancelled", cancelled)
        if (value != null) o.put("value", value)
        if (confirmed != null) o.put("confirmed", confirmed)
        sendRaw(o.toString())
    }

    /** İyimser balon: metin + tahminî @satırlar (web submit deseni). */
    fun optimisticUser(text: String, fileNames: List<String>) {
        val atLines = if (attachDir != null) fileNames.map { "@$attachDir/${sanitizeName(it)}" } else emptyList()
        val full = text + (if (atLines.isNotEmpty()) (if (text.isNotEmpty()) "\n" else "") + atLines.joinToString("\n") else "")
        val parts = if (full.isNotEmpty()) listOf(Part.Text(full)) else emptyList()
        _messages.value = _messages.value + ChatMsg("user", parts)
    }

    fun toggleLiveThinking(index: Int) {
        scope.launch {
            val s = liveWorking.getOrNull(index) ?: return@launch
            if (s is LiveSeg.Thinking) {
                liveWorking[index] = s.copy(expanded = !s.expanded)
                liveDirty = true
            }
        }
    }

    fun pushNotice(text: String, level: String = "info") {
        if (text.isBlank()) return
        val at = _messages.value.size + (if (_live.value.isNotEmpty()) 1 else 0)
        val list = _notices.value + Notice(text, level, at)
        _notices.value = if (list.size > 30) list.drop(list.size - 30) else list
    }

    private fun forView(o: JSONObject): Boolean {
        if (!o.has("slot")) return true
        val s = try { o.getInt("slot") } catch (_: Exception) { return true }
        return viewSlot == null || s == viewSlot
    }

    private fun applyState(s: JSONObject?) {
        if (s == null) return
        _stateTick.value++
        // safeNull ile "null" stringi önlenir
        sessionName = s.safeNull("sessionName")
        sessionFile = s.safeNull("sessionFile")
        val m = s.optJSONObject("model")
        if (m != null) {
            modelProvider = m.safeNull("provider")
            modelId = m.safeNull("id")
            modelName = m.safeNull("name")
            modelContextWindow = if (m.isNull("contextWindow")) null else m.optLong("contextWindow")
        } else { modelProvider = null; modelId = null; modelName = null; modelContextWindow = null }
        thinkingLevel = s.optString("thinkingLevel", "off")
        thinkingLevels = s.optJSONArray("thinkingLevels")?.let { arr ->
            List(arr.length()) { arr.optString(it) }
        }
        if (s.has("isStreaming")) _streaming.value = s.optBoolean("isStreaming", _streaming.value)
    }

    private fun onJson(raw: String) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "hello" -> {
                _booting.value = false
                attachDir = o.safeNull("attachDir")
                o.optInt("slot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { viewSlot = it }
                _statuses.value = o.optJSONArray("statuses")?.let { a -> List(a.length()) { a.optJSONObject(it)?.optString("text", "") ?: "" } } ?: emptyList()
                _widgets.value = o.optJSONArray("widgets")?.let { a ->
                    List(a.length()) { i ->
                        a.optJSONObject(i)?.optJSONArray("lines")?.let { l -> List(l.length()) { l.optString(it) } }?.joinToString("\n") ?: ""
                    }
                } ?: emptyList()
                applyState(o.optJSONObject("state"))
            }
            "booting" -> _booting.value = true
            "ready", "state" -> if (forView(o)) applyState(o.optJSONObject("state"))
            "commands" -> {
                val parsed = parseCommands(o.optJSONArray("commands"))
                val existingNames = parsed.map { it.name }.toSet()
                // Yerleşik komutlarla dinamik komutları birleştir
                val merged = parsed + BUILTIN_COMMANDS.filter { it.name !in existingNames }
                _commands.value = merged
            }
            "settled" -> {
                val slot = o.optInt("slot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                if (slot != null && viewSlot != null && slot != viewSlot) {
                    listSessions()
                    pushNotice("✓ başka oturum hazır")
                    return
                }
                if (slot != null) viewSlot = slot
                _messages.value = parseMessages(o.optJSONArray("messages"))
                liveWorking.clear(); liveDirty = true; _live.value = emptyList()
                _stats.value = parseStats(o.optJSONObject("stats"))
                applyState(o.optJSONObject("state"))
            }
            "switched" -> {
                o.optInt("slot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { viewSlot = it }
                _messages.value = parseMessages(o.optJSONArray("messages"))
                _notices.value = emptyList()
                liveWorking.clear(); liveDirty = true; _live.value = emptyList()
                _streaming.value = false
                _stats.value = parseStats(o.optJSONObject("stats"))
                applyState(o.optJSONObject("state"))
                listSessions()
            }
            "streaming" -> if (forView(o)) _streaming.value = o.optBoolean("streaming", false)
            "delta" -> {
                if (!forView(o)) return
                _streaming.value = true
                liveDelta(if (o.optString("kind") == "thinking") "thinking" else "text", o.optString("delta", ""))
            }
            "toolcall_start" -> {
                if (!forView(o)) return
                val id = o.safeNull("id")
                val name = o.safeNull("toolName")
                if (id != null && name != null) liveTool(id, name, null, "start", null, false)
            }
            "toolcall_end" -> {
                if (!forView(o)) return
                val id = o.safeNull("id")
                val seg = liveWorking.filterIsInstance<LiveSeg.Tool>().find { it.id != null && it.id == id }
                if (seg != null && seg.phase == "running") { seg.phase = "end"; liveDirty = true }
            }
            "tool" -> {
                if (!forView(o)) return
                liveTool(
                    o.safeNull("toolCallId"),
                    o.optString("toolName", "?"),
                    if (o.isNull("args")) null else o.opt("args"),
                    o.optString("phase", "start"),
                    o.safeNull("output"),
                    o.optBoolean("isError", false),
                )
            }
            "sessions" -> {
                val arr = o.optJSONArray("sessions"); val run = o.optJSONArray("running")
                val running = mutableSetOf<String>()
                if (run != null) for (i in 0 until run.length()) running += run.optString(i)
                val list = mutableListOf<SessionInfo>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val path = s.optString("path", "")
                    list += SessionInfo(
                        path,
                        s.safeNull("name"), s.safeNull("title"),
                        s.optLong("modified", 0), s.optInt("messageCount", 0),
                        path in running,
                    )
                }
                _sessions.value = list
            }
            "models" -> {
                val arr = o.optJSONArray("models"); val list = mutableListOf<ModelInfo>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    list += ModelInfo(m.optString("provider"), m.optString("id"), m.optString("name", m.optString("id")))
                }
                _models.value = list
            }
            "notice" -> if (forView(o)) pushNotice(o.optString("text", ""), o.optString("level", "info"))
            "dialog" -> _dialog.value = DialogState(
                if (o.isNull("id")) null else o.optInt("id"),
                o.optString("kind", "select"), o.optString("title", ""),
                o.optString("message", ""),
                o.optJSONArray("options")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                o.optString("placeholder", ""), o.optString("prefill", ""),
                _commands.value,
            )
            "dialog_closed" -> {
                val id = if (o.isNull("id")) null else o.optInt("id")
                if (_dialog.value?.id == id) _dialog.value = null
            }
            "queue" -> {
                if (!forView(o)) return
                _queueSteer.value = o.optJSONArray("steering")?.length() ?: 0
                _queueFollow.value = o.optJSONArray("followUp")?.length() ?: 0
            }
            "statuses" -> {
                _statuses.value = o.optJSONArray("statuses")?.let { a -> List(a.length()) { a.optJSONObject(it)?.optString("text", "") ?: "" } } ?: emptyList()
            }
            "widgets" -> {
                _widgets.value = o.optJSONArray("widgets")?.let { a ->
                    List(a.length()) { i ->
                        a.optJSONObject(i)?.optJSONArray("lines")?.let { l -> List(l.length()) { l.optString(it) } }?.joinToString("\n") ?: ""
                    }
                } ?: emptyList()
            }
            "fatal" -> toast(o.optString("text", "hata"), "error")
            "ping" -> { }
        }
    }

    private fun liveDelta(kind: String, delta: String) {
        if (delta.isEmpty()) return
        val last = liveWorking.lastOrNull()
        if (kind == "thinking" && last is LiveSeg.Thinking) liveWorking[liveWorking.lastIndex] = last.copy(text = (last.text + delta).takeLast(100_000))
        else if (kind == "text" && last is LiveSeg.Text) liveWorking[liveWorking.lastIndex] = last.copy(text = (last.text + delta).takeLast(100_000))
        else liveWorking += if (kind == "thinking") LiveSeg.Thinking(delta) else LiveSeg.Text(delta)
        liveDirty = true
    }

    private fun liveTool(id: String?, name: String, args: Any?, phase: String, output: String?, isError: Boolean) {
        var seg = liveWorking.filterIsInstance<LiveSeg.Tool>().find { it.id == id }
        if (seg == null) {
            seg = LiveSeg.Tool(id, name, "", args, "", "running", false)
            liveWorking += seg
        }
        if (name.isNotBlank() && name != "?") seg.name = name
        when (phase) {
            "start" -> { seg.args = summarizeArgs(args); seg.argsRaw = args; seg.phase = "running" }
            "update" -> { seg.output = (output ?: "").takeLast(100_000); seg.phase = "running" }
            "end" -> { seg.output = (output ?: "").takeLast(100_000); seg.phase = "end"; seg.isError = isError }
        }
        liveDirty = true
    }

    private fun parseCommands(arr: JSONArray?): List<Cmd> {
        val out = mutableListOf<Cmd>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out += Cmd(c.optString("name"), c.optString("description", ""), c.optString("descriptionEn", ""), c.optString("source", ""))
        }
        return out
    }

    private fun parseStats(o: JSONObject?): Stats? {
        if (o == null) return null
        fun lng(k: String) = if (o.isNull(k)) null else o.optLong(k)
        fun dbl(k: String) = if (o.isNull(k)) null else o.optDouble(k)
        val s = Stats(lng("totalTokens"), dbl("cost"), dbl("contextPercent"), lng("contextWindow"))
        return if (s.totalTokens == null && s.cost == null && s.contextPercent == null) null else s
    }

    private fun parseMessages(arr: JSONArray?): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        if (arr == null) return out
        toolResults.clear()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", m.optString("type", "?"))
            if (role == "toolResult") {
                val text = m.optJSONArray("content")?.let { c ->
                    List(c.length()) { c.optJSONObject(it) }
                        .filter { it?.optString("type") == "text" }
                        .joinToString("\n") { it?.optString("text", "") ?: "" }
                } ?: ""
                m.safeNull("toolCallId")?.let { toolResults[it] = text.take(4000) }
                continue
            }
            if (role == "bashExecution") {
                out += ChatMsg(role, emptyList(), m.optString("command", ""), m.optString("output", ""))
                continue
            }
            if (role != "user" && role != "assistant") continue
            val parts = mutableListOf<Part>()
            val c = m.opt("content")
            if (c is String) { if (c.isNotBlank()) parts += Part.Text(c) }
            else if (c is JSONArray) {
                for (j in 0 until c.length()) {
                    val p = c.optJSONObject(j) ?: continue
                    when (p.optString("type")) {
                        "text" -> p.optString("text", "").takeIf { it.isNotBlank() }?.let { parts += Part.Text(it) }
                        "thinking" -> p.optString("thinking", "").takeIf { it.isNotBlank() }?.let { parts += Part.Thinking(it) }
                        "toolCall", "tool_use" -> {
                            val id = p.safeNull("id")
                            val outText = id?.let { toolResults[it] } ?: ""
                            parts += Part.ToolCall(
                                id,
                                p.optString("name", p.optString("toolName", "?")),
                                summarizeArgs(p.opt("arguments") ?: p.opt("args")),
                                p.opt("arguments") ?: p.opt("args"),
                                outText,
                            )
                        }
                        "image" -> parts += Part.Image(p.safeNull("mimeType"))
                    }
                }
            }
            if (parts.isNotEmpty()) out += ChatMsg(role, parts)
        }
        return out
    }

    /** SAF uri → {name, mime, data(base64)} (gateway limiti: 10 dosya, 25MB). */
    suspend fun stageUris(cr: ContentResolver, uris: List<Uri>): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        for (u in uris.take(10)) {
            try {
                val mime = cr.getType(u) ?: "application/octet-stream"
                var name = "dosya"
                cr.query(u, null, null, null, null)?.use { cur ->
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cur.moveToFirst() && idx >= 0) name = cur.getString(idx) ?: name
                }
                cr.openInputStream(u)?.use { ins ->
                    val bytes = ins.readBytes()
                    if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) return@use
                    out.put(JSONObject().put("name", name).put("mime", mime)
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }
            } catch (_: Exception) { }
        }
        out
    }

    fun pendingNames(files: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until files.length()) out += files.optJSONObject(i)?.optString("name", "dosya") ?: "dosya"
        return out
    }
}

/**
 * Android JSONObject.optString("key", null) eğer JSON null ise "null" stringi döndürür.
 * Bu yardımcı fonksiyon gerçek null döner ve boşluk/literal "null" durumlarını temizler.
 */
fun JSONObject.safeNull(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key, "")
    if (v.isEmpty() || v == "null") return null
    return v
}
