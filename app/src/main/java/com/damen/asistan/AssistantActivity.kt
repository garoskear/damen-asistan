package com.damen.asistan

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import java.io.FileOutputStream
import java.util.Locale

/**
 * Varsayılan asistan çağrısı (ASSIST) buraya düşer. Yarı saydam:
 * - Açılışta arka planda anında ekran görüntüsü yakalar (asistan arayüzü gelmeden).
 * - Kullanıcı isterse tek dokunuşla son çekilen ekran görüntüsünü mesaja ekler.
 * - Dahili mikrofon (SpeechRecognizer) ile sesli mesaj desteği.
 * - WebSocket önceden bağlanır, gönderim anında ana sohbete akıcı geçiş yapılır.
 */
class AssistantActivity : ComponentActivity() {

    private val client by lazy { GwClient() }
    internal var shotReceiver: BroadcastReceiver? = null
    private var lastAutoShotPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AsistanConfig.load(this)

        // Asistan açıldığı an arka planda sessizce ekran görüntüsü al
        PowerService.captureScreen { path ->
            lastAutoShotPath = path
        }

        // Gateway bağlantısını önceden kur — kullanıcı yazarken WS hazır olsun
        if (cfg.token.isNotBlank() && cfg.token != "BURAYA_TOKEN") {
            client.connect(cfg.token)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Transparent, surface = Color.Transparent)) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                    AssistantScreen(
                        cfg = cfg,
                        client = client,
                        initialAutoShot = lastAutoShotPath,
                        onDone = { cleanTempFiles(); finish() },
                        onOpenMain = {
                            cleanTempFiles()
                            val it = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(it)
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun cleanTempFiles() {
        try {
            lastAutoShotPath?.let { File(it).delete() }
            File(cacheDir, "auto_shot.png").delete()
        } catch (_: Exception) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tekrar çağrılırsa yeni ekran karesini al
        PowerService.captureScreen { path ->
            lastAutoShotPath = path
        }
    }

    override fun onDestroy() {
        try { shotReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
        shotReceiver = null
        cleanTempFiles()
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
    initialAutoShot: String?,
    onDone: () -> Unit,
    onOpenMain: () -> Unit,
) {
    val ctx = LocalContext.current
    val act = ctx as? AssistantActivity
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("damen", Context.MODE_PRIVATE) }

    var input by remember { mutableStateOf("") }
    var pendingFiles by remember { mutableStateOf(JSONArray()) }
    var autoShotFile by remember { mutableStateOf(initialAutoShot) }
    var plusOpen by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var shotJob by remember { mutableStateOf<Job?>(null) }

    // Açılışta otomatik çekilen kareyi bekle (captureScreen async — en fazla 1.5 sn)
    LaunchedEffect(Unit) {
        if (autoShotFile == null) {
            repeat(15) {
                delay(100)
                val f = File(ctx.cacheDir, "auto_shot.png")
                if (f.exists() && f.length() > 0) {
                    autoShotFile = f.absolutePath
                    return@LaunchedEffect
                }
            }
        }
    }

    fun showToast(t: String) {
        toast = t
        scope.launch { delay(2500); if (toast == t) toast = null }
    }

    // Mikrofon ve konuşma tanıma (SpeechRecognizer)
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
        isListening = false
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            showToast("ses tanıma servisi bu cihazda yok (microG ortamında Google ses servisi gerekir)")
            return
        }
        stopListening()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> null // sessizlik — kullanıcı konuşmadı, uyarıya gerek yok
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mikrofon izni verilmedi"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ses servisi meşgul, tekrar dene"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ses algılanamadı"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "ses servisi ağa ulaşamadı"
                    SpeechRecognizer.ERROR_CLIENT -> "ses servisi yanıt vermiyor (cihazda ses tanıma yok olabilir)"
                    else -> "mikrofon hatası ($error)"
                }
                if (msg != null) showToast(msg)
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank()) {
                    input = if (input.isBlank()) spoken else "$input $spoken"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                // Anlık kısmi sonucu alana düşür — geri bildirim hissi
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank() && isListening) {
                    // partial'ı sadece placeholder'a yansıtmak yerine sessizce bekliyoruz —
                    // bazı cihazlarda partial final'i yineliyor, çift metin oluşmasın.
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            r.startListening(intent)
            isListening = true
        } catch (_: Exception) {
            isListening = false
            showToast("mikrofon başlatılamadı")
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening()
        else showToast("mikrofon izni gerekli")
    }

    fun toggleMic() {
        if (isListening) {
            stopListening()
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) startListening()
            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Ekran görüntüsü ekleme: 4K ekran PNG'si ~5-10MB — okuma/base64 IO'da yapılır,
    // ana thread'de büyük bellek işlemi çökme/ANR yaratmaz.
    fun attachAutoScreenshot() {
        val f = autoShotFile?.let { File(it) } ?: File(ctx.cacheDir, "auto_shot.png")
        if (!f.exists() || f.length() <= 0L) { showToast("ekran görüntüsü bulunamadı"); return }
        val shotPath = f.absolutePath
        DamenLog.log("SHOT", "attach start: $shotPath (${f.length()} bytes)")
        scope.launch {
            try {
                // 4K ekran PNG'si ~8MB; base64'ü 10MB'a çıkıyordu (bellek sıçraması = çökme şüphesi).
                // PNG -> JPEG(preview) dönüşümüyle boyut 10x düşer; dönüşüm olmazsa ham PNG kalır.
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var mime = "image/png"
                    var bytes: ByteArray = File(shotPath).readBytes()
                    try {
                        val bmp = BitmapFactory.decodeFile(shotPath)
                        if (bmp != null) {
                            val tmp = File(ctx.cacheDir, "attach_preview.jpg")
                            FileOutputStream(tmp).use { fos ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                            }
                            bmp.recycle()
                            bytes = tmp.readBytes()
                            tmp.delete()
                            mime = "image/jpeg"
                        }
                    } catch (_: Exception) { /* PNG fallback */ }
                    mime to bytes
                }
                val (mime, bytes) = res
                DamenLog.log("SHOT", "converted: $mime ${bytes.size} bytes")
                if (bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) {
                    val b64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                    DamenLog.log("SHOT", "b64 OK: ${b64.length} chars")
                    val merged = JSONArray()
                    for (i in 0 until pendingFiles.length()) merged.put(pendingFiles.get(i))
                    merged.put(JSONObject().put("name", "ekran.jpeg").put("mime", mime).put("data", b64))
                    pendingFiles = merged
                    DamenLog.log("SHOT", "attached OK: ${merged.length()} files")
                    showToast("+ ekran görüntüsü eklendi")
                } else {
                    DamenLog.log("SHOT", "too large: ${bytes.size}")
                    showToast("ekran görüntüsü çok büyük (8MB)")
                }
            } catch (e: Exception) {
                DamenLog.log("SHOT", "attach FAIL: ${e}")
                showToast("eklenemedi: ${e.message ?: "hata"}")
            }
        }
    }

    val cropLauncher = rememberLauncherForActivityResult(
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

    val mpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == ComponentActivity.RESULT_OK && res.data != null) {
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

    val filePicker = rememberLauncherForActivityResult(
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

    // Akıcı ve anlık gönderim: bekletmeden prompt yolla ve ana sohbet ekranına yumuşakça geç
    fun submit() {
        val text = input.trim()
        if (sending) return
        if (text.isEmpty() && pendingFiles.length() == 0) return
        if (cfg.token.isBlank() || cfg.token == "BURAYA_TOKEN") {
            showToast("önce ana uygulamadan token gir")
            return
        }
        stopListening()
        sending = true
        plusOpen = false
        val files = pendingFiles

        scope.launch {
            try {
                // Eğer soket henüz açık değilse hızlıca bağlanmasını bekle (en fazla 2 sn)
                if (client.conn.value != Conn.Open) {
                    client.connect(cfg.token)
                    withTimeoutOrNull(2000) { client.conn.first { it == Conn.Open } }
                }
                // Oturumu doğrula
                val saved = prefs.getString("assistant_session", null)
                if (!saved.isNullOrBlank()) {
                    client.switchSession(saved)
                }
                // Mesajı fırlat
                client.sendPrompt(text, files)
                // Hemen ana uygulamaya geç — akışkan, gecikmesiz
                onOpenMain()
            } catch (_: Exception) {
                showToast("gönderilemedi")
                sending = false
            }
        }
    }

    if (hidden) {
        Box(Modifier.fillMaxSize())
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onDone() },
    ) {
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
                    PlusRow("Ekran Görüntüsü Ekle", "açılışta otomatik çekilen ekranı mesaja ekle") {
                        plusOpen = false
                        attachAutoScreenshot()
                    }
                    PlusRow("Kırparak Yakala", "ekran görüntüsünü seçip kırparak ekle") {
                        plusOpen = false
                        val shotPath = autoShotFile ?: File(ctx.cacheDir, "auto_shot.png").takeIf { it.exists() }?.absolutePath
                        if (shotPath != null) {
                            cropLauncher.launch(Intent(ctx, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, shotPath))
                        } else {
                            val mp = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mpLauncher.launch(mp.createScreenCaptureIntent())
                        }
                    }
                    PlusRow("Dosya Seç", "cihazdan dosya veya resim ekle") {
                        plusOpen = false
                        filePicker.launch(arrayOf("*/*"))
                    }
                }
            }

            if (pendingFiles.length() > 0) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChipRow(client.pendingNames(pendingFiles)) { idx ->
                        val next = JSONArray()
                        for (i in 0 until pendingFiles.length()) if (i != idx) next.put(pendingFiles.get(i))
                        pendingFiles = next
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp, 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // + menüsü
                Box(
                    modifier = Modifier.height(48.dp).width(36.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { plusOpen = !plusOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (plusOpen) "✕" else "＋", fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Damen.Dim)
                }

                // Hızlı ekran görüntüsü ekleme tuşu (otomatik çekilen kareyi hemen ekler)
                Box(
                    modifier = Modifier.height(48.dp).width(36.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { attachAutoScreenshot() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⛶", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = if (autoShotFile != null) Damen.Fg else Damen.Faint)
                }

                // Metin alanı
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (isListening) "dinleniyor…" else "pi'ye yaz…",
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                            color = if (isListening) Damen.Accent else Damen.Faint,
                        )
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = Damen.Fg),
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )

                // Mikrofon tuşu
                Box(
                    modifier = Modifier.height(48.dp).width(40.dp)
                        .border(1.dp, if (isListening) Damen.Accent else Damen.Line)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { toggleMic() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isListening) {
                        val inf = rememberInfiniteTransition(label = "mic")
                        val a by inf.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "mic")
                        Text("🎙", fontSize = 16.sp, modifier = Modifier.alpha(a))
                    } else {
                        Text("🎙", fontSize = 16.sp, color = Damen.Dim)
                    }
                }

                // Gönder tuşu
                val canSend = input.trim().isNotEmpty() || pendingFiles.length() > 0
                Box(
                    modifier = Modifier.height(48.dp).width(44.dp)
                        .border(1.dp, if (canSend) Damen.Accent else Damen.Line)
                        .clickable(enabled = canSend && !sending) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) {
                        Text("…", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Damen.Dim)
                    } else {
                        Box(Modifier.size(14.dp).background(if (canSend) Damen.Accent else Damen.Faint))
                    }
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
