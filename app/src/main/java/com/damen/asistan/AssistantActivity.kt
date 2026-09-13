package com.damen.asistan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Varsayılan asistan çağrısı (ASSIST) buraya düşer. Yarı saydam:
 * altta mesaj barı + gönder, en sağda dikey pill kısayol barı, barın solunda + popup'ı.
 * Asistan konuşmaları normal session'dır — gönderim ana sohbetteki oturuma gider.
 */
class AssistantActivity : ComponentActivity() {

    private val client by lazy { GwClient() }
    private var shotReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AsistanConfig.load(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Transparent, surface = Color.Transparent)) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                    AssistantScreen(cfg, client,
                        onDone = { finish() },
                        onOpenMain = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        try { shotReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
        shotReceiver = null
        client.disconnect()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOT = "com.damen.asistan.SHOT"
        const val EXTRA_SHOT = "shot"
    }
}

@Composable
private fun AssistantScreen(
    cfg: AsistanConfig,
    client: GwClient,
    onDone: () -> Unit,
    onOpenMain: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val act = ctx as? AssistantActivity
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("damen", Context.MODE_PRIVATE) }

    var input by remember { mutableStateOf("") }
    var pendingFiles by remember { mutableStateOf(JSONArray()) }
    var plusOpen by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var shotJob by remember { mutableStateOf<Job?>(null) }

    fun showToast(t: String) {
        toast = t
        scope.launch { delay(2500); if (toast == t) toast = null }
    }

    val cropLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        hidden = false
        shotJob?.cancel()
        if (res.resultCode == ComponentActivity.RESULT_OK) {
            val path = res.data?.getStringExtra(CropActivity.EXTRA_OUT)
            try {
                if (path != null) {
                    val bytes = File(path).readBytes()
                    if (bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) {
                        val merged = JSONArray()
                        for (i in 0 until pendingFiles.length()) merged.put(pendingFiles.get(i))
                        merged.put(
                            JSONObject().put("name", "ekran.png").put("mime", "image/png")
                                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)),
                        )
                        pendingFiles = merged
                        showToast("+ ekran.png")
                    }
                }
            } catch (_: Exception) { showToast("ek eklenemedi") }
        }
    }

    val mpLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == ComponentActivity.RESULT_OK && res.data != null) {
            // Asistanı gizle (kareye girmesin) → servis yakalar → yayınla CropActivity'yi aç
            hidden = true
            val recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val shot = intent?.getStringExtra(AssistantActivity.EXTRA_SHOT) ?: return
                    shotJob?.cancel()
                    try { act?.unregisterReceiver(this) } catch (_: Exception) { }
                    act?.shotReceiver = null
                    cropLauncher.launch(Intent(ctx, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, shot))
                }
            }
            act?.shotReceiver = recv
            ContextCompat.registerReceiver(ctx, recv, IntentFilter(AssistantActivity.ACTION_SHOT), ContextCompat.RECEIVER_NOT_EXPORTED)
            val svc = Intent(ctx, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, res.data)
            ContextCompat.startForegroundService(ctx, svc)
            shotJob = scope.launch {
                delay(15000)
                hidden = false
                showToast("yakalanamadı")
                try { act?.unregisterReceiver(recv) } catch (_: Exception) { }
            }
        } else {
            showToast("izin verilmedi")
        }
    }

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = client.stageUris(ctx.contentResolver, uris)
            val merged = JSONArray()
            for (i in 0 until pendingFiles.length()) merged.put(pendingFiles.get(i))
            for (i in 0 until staged.length()) merged.put(staged.get(i))
            if (merged.length() > 0) showToast("+ ${staged.length()} dosya")
            pendingFiles = merged
        }
    }

    fun runShortcut(s: Shortcut) {
        when (s.action) {
            "power_dialog" -> {
                if (!PowerService.showPowerDialog()) {
                    showToast("erişilebilirlikte Damen'e izin ver")
                    try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                }
            }
            else -> {
                val pkg = s.packageName
                if (pkg != null) {
                    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        try { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { showToast("açılamadı") }
                    } else showToast("bulunamadı: $pkg")
                }
            }
        }
    }

    fun submit() {
        val text = input.trim()
        if (sending) return
        if (text.isEmpty() && pendingFiles.length() == 0) return
        if (cfg.token.isBlank() || cfg.token == "BURAYA_TOKEN") {
            showToast("önce ana uygulamadan token gir")
            return
        }
        sending = true
        plusOpen = false
        val files = pendingFiles
        scope.launch {
            try {
                client.connect(cfg.token)
                val hello = withTimeoutOrNull(15000) { client.stateTick.first { client.sessionFile != null } }
                if (hello == null) { showToast("gateway yok"); sending = false; return@launch }
                val saved = prefs.getString("assistant_session", null)
                var switched = false
                if (!saved.isNullOrBlank()) {
                    client.switchSession(saved)
                    switched = withTimeoutOrNull(10000) {
                        client.stateTick.first { client.sessionFile == saved }
                        true
                    } ?: false
                }
                if (!switched) {
                    client.newSession()
                    withTimeoutOrNull(15000) { client.stateTick.first { client.sessionFile != null } }
                }
                client.sendPrompt(text, files)
                try { prefs.edit().putString("assistant_session", client.sessionFile).apply() } catch (_: Exception) { }
                input = ""
                pendingFiles = JSONArray()
                showToast("gönderildi ✓")
                delay(600)
                onOpenMain()
            } catch (_: Exception) {
                showToast("gönderilemedi")
            } finally {
                sending = false
            }
        }
    }

    if (hidden) {
        // Yakalama anı: tamamen şeffaf (kareye girmeyelim)
        Box(Modifier.fillMaxSize())
        return
    }

    Box(Modifier.fillMaxSize().clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onDone() }) {
        // Sağda dikey pill bar (config.json kısayolları)
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                .background(Color(0xCC000000))
                .border(1.dp, Damen.Line)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            cfg.shortcuts.forEach { s ->
                Box(
                    modifier = Modifier.size(48.dp).border(1.dp, Damen.LineDim).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { runShortcut(s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.label.take(1).uppercase(), fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp, color = Damen.Fg,
                    )
                }
            }
        }

        // Altta mesaj barı
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xEE000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        ) {
            if (plusOpen) {
                Column(Modifier.fillMaxWidth().padding(10.dp).background(Damen.Surface).border(1.dp, Damen.Line)) {
                    PlusRow("Ekran görüntüsü", "asistan dışı ekranı çek + kırp") {
                        plusOpen = false
                        val mp = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mpLauncher.launch(mp.createScreenCaptureIntent())
                    }
                    PlusRow("Dosya", "mesaja dosya ekle") {
                        plusOpen = false
                        filePicker.launch(arrayOf("*/*"))
                    }
                }
            }
            if (pendingFiles.length() > 0) {
                ChipRow(client.pendingNames(pendingFiles)) { idx ->
                    val next = JSONArray()
                    for (i in 0 until pendingFiles.length()) if (i != idx) next.put(pendingFiles.get(i))
                    pendingFiles = next
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp, 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.height(48.dp).width(36.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { plusOpen = !plusOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (plusOpen) "✕" else "＋", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Damen.Dim)
                }
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("pi'ye yaz…", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Damen.Faint) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = Damen.Fg),
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Box(
                    modifier = Modifier.height(48.dp).width(44.dp)
                        .border(1.dp, if (sending) Damen.Line else Damen.Accent)
                        .clickable(enabled = !sending) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) Text("…", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Damen.Dim)
                    else Box(Modifier.size(14.dp).background(Damen.Accent))
                }
            }
        }

        toast?.let { t ->
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
                    .background(Damen.Surface2).border(1.dp, Damen.Line)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(t, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Damen.Fg) }
        }
    }
}

@Composable
private fun PlusRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Damen.Fg)
        Text(desc, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Damen.Faint, modifier = Modifier.weight(1f))
    }
}
