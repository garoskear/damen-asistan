package com.damen.asistan

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
data class ChatMsg(val role: String, val text: String, val thinking: String = "")
data class SessionInfo(val path: String, val name: String?, val running: Boolean = false)
data class ModelInfo(val provider: String, val id: String, val name: String)

sealed interface Conn { data object Idle : Conn; data object Connecting : Conn; data object Open : Conn; data class Error(val text: String) : Conn }

class GwClient(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    private val _conn = MutableStateFlow<Conn>(Conn.Idle); val conn: StateFlow<Conn> = _conn
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList()); val messages: StateFlow<List<ChatMsg>> = _messages
    private val _liveText = MutableStateFlow(""); val liveText: StateFlow<String> = _liveText
    private val _liveThinking = MutableStateFlow(""); val liveThinking: StateFlow<String> = _liveThinking
    private val _streaming = MutableStateFlow(false); val streaming: StateFlow<Boolean> = _streaming
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList()); val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList()); val models: StateFlow<List<ModelInfo>> = _models
    private val _notice = MutableStateFlow<String?>(null); val notice: StateFlow<String?> = _notice
    private val _booting = MutableStateFlow(false); val booting: StateFlow<Boolean> = _booting
    private val _modelLabel = MutableStateFlow("model"); val modelLabel: StateFlow<String> = _modelLabel
    private val _thinkingLabel = MutableStateFlow("off"); val thinkingLabel: StateFlow<String> = _thinkingLabel

    var viewSlot: Int? = null
        private set
    var token: String = ""

    fun connect(token: String) {
        this.token = token
        disconnect()
        _conn.value = Conn.Connecting
        val req = Request.Builder().url(AsistanConfig.wsUrl(token)).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _conn.value = Conn.Open
                sendRaw(JSONObject().put("type", "visibility").put("visible", true).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { onJson(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _conn.value = Conn.Error(t.message ?: "bağlantı hatası")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _conn.value = Conn.Error("kapandı: $reason")
            }
        })
    }

    fun disconnect() { try { ws?.close(1000, "bye") } catch (_: Exception) { }; ws = null }

    private fun sendRaw(s: String) { try { ws?.send(s) } catch (_: Exception) { } }

    fun sendPrompt(text: String, files: JSONArray = JSONArray(), slot: Int? = viewSlot) {
        val o = JSONObject().put("type", "prompt").put("text", text).put("files", files)
        if (slot != null) o.put("slot", slot)
        sendRaw(o.toString())
    }
    fun abort() { val o = JSONObject().put("type", "abort"); viewSlot?.let { o.put("slot", it) }; sendRaw(o.toString()) }
    fun newSession() = sendRaw(JSONObject().put("type", "new_session").toString())
    fun listSessions() = sendRaw(JSONObject().put("type", "list_sessions").toString())
    fun switchSession(path: String) = sendRaw(JSONObject().put("type", "switch_session").put("path", path).toString())
    fun listModels() = sendRaw(JSONObject().put("type", "list_models").toString())
    fun setModel(provider: String, modelId: String) {
        val o = JSONObject().put("type", "set_model").put("provider", provider).put("modelId", modelId)
        viewSlot?.let { o.put("slot", it) }; sendRaw(o.toString())
    }
    fun setThinking(level: String) {
        val o = JSONObject().put("type", "set_thinking").put("level", level)
        viewSlot?.let { o.put("slot", it) }; sendRaw(o.toString())
    }

    fun clearNotice() { _notice.value = null }

    private fun forView(o: JSONObject): Boolean {
        if (!o.has("slot")) return true
        val s = try { o.getInt("slot") } catch (_: Exception) { return true }
        return viewSlot == null || s == viewSlot
    }

    private fun onJson(raw: String) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "hello" -> {
                _booting.value = false
                o.optInt("slot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { viewSlot = it }
                applyState(o.optJSONObject("state"))
            }
            "booting" -> _booting.value = true
            "ready", "state" -> if (forView(o)) applyState(o.optJSONObject("state"))
            "settled" -> {
                val slot = o.optInt("slot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                if (slot != null && viewSlot != null && slot != viewSlot) {
                    listSessions()
                    _notice.value = "✓ başka oturum hazır"
                    return
                }
                if (slot != null) viewSlot = slot
                _messages.value = parseMessages(o.optJSONArray("messages"))
                _liveText.value = ""; _liveThinking.value = ""
                applyState(o.optJSONObject("state"))
            }
            "switched" -> {
                o.optInt("slot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { viewSlot = it }
                _messages.value = parseMessages(o.optJSONArray("messages"))
                _liveText.value = ""; _liveThinking.value = ""; _streaming.value = false
                applyState(o.optJSONObject("state"))
                listSessions()
            }
            "streaming" -> if (forView(o)) _streaming.value = o.optBoolean("streaming", false)
            "delta" -> {
                if (!forView(o)) return
                _streaming.value = true
                if (o.optString("kind") == "thinking") _liveThinking.value += o.optString("delta", "")
                else _liveText.value += o.optString("delta", "")
            }
            "sessions" -> {
                val arr = o.optJSONArray("sessions"); val run = o.optJSONArray("running")
                val running = mutableSetOf<String>()
                if (run != null) for (i in 0 until run.length()) running += run.optString(i)
                val list = mutableListOf<SessionInfo>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val path = s.optString("path", "")
                    list += SessionInfo(path, s.optString("name", "").ifBlank { null }, path in running)
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
            "notice" -> if (forView(o)) _notice.value = o.optString("text", "")
            "fatal" -> _notice.value = o.optString("text", "fatal")
            "ping" -> { }
        }
    }

    private fun applyState(s: JSONObject?) {
        if (s == null) return
        val m = s.optJSONObject("model")
        _modelLabel.value = if (m != null) "${m.optString("provider")}/${m.optString("id")}" else "model"
        _thinkingLabel.value = s.optString("thinkingLevel", "off")
        if (s.has("isStreaming")) _streaming.value = s.optBoolean("isStreaming", _streaming.value)
    }

    /** pi SDK mesajları: {role, content: string | [{type:text.tool...}]} — v1 basit metin özet. */
    private fun parseMessages(arr: JSONArray?): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", m.optString("type", "?"))
            val c = m.opt("content")
            val sb = StringBuilder(); val th = StringBuilder()
            if (c is String) sb.append(c)
            else if (c is JSONArray) {
                for (j in 0 until c.length()) {
                    val p = c.optJSONObject(j) ?: continue
                    when (p.optString("type")) {
                        "text" -> sb.append(p.optString("text", ""))
                        "thinking" -> th.append(p.optString("thinking", p.optString("text", "")))
                        "tool_use", "toolcall" -> sb.append("\n[tool: ${p.optString("name", p.optString("toolName", "?"))}]")
                        "tool_result" -> { /* v1'de gövdeyi şişirmesin */ }
                        else -> { val t = p.optString("text", ""); if (t.isNotBlank()) sb.append(t) }
                    }
                }
            }
            val text = sb.toString().trim()
            if (text.isBlank() && th.toString().isBlank()) continue
            out += ChatMsg(role, text, th.toString().trim())
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
}
