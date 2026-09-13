package com.damen.asistan

/* Hallmark · pre-emit critique: P5 H4 E5 S4 R5 V5
 * Kaynak DNA: damen-gateway/web/style.css — Nothing monokrom + hairline + mono + tek kırmızı.
 * Token dışına renk/yazı çıkılmaz. */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/** style.css :root birebiri. */
object Damen {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF101010)
    val DrawerBg = Color(0xFF0A0A0A)
    val Surface2 = Color(0xFF181818)
    val Line = Color(0xFF2A2A2A)
    val LineDim = Color(0xFF1C1C1C)
    val Fg = Color(0xFFF5F5F5)
    val Dim = Color(0xFF9A9A9A)
    val Faint = Color(0xFF6A6A6A)
    val Accent = Color(0xFFD71921)
    val AccentDim = Color(0xFF7A0E12)
    val Ok = Color(0xFF7FA895)
    val RunBg = Color(0xFF12141A)
    val OkBg = Color(0xFF0E1412)
    val ErrBg = Color(0xFF150A0C)
    val LiveGreen = Color(0xFF00FF88)
    val Mono = FontFamily.Monospace
}

/** .micro etiketi: 10sp, harf aralıklı, büyük harf, soluk. */
@Composable
fun Micro(text: String, color: Color = Damen.Dim, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            fontFamily = Damen.Mono,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            color = color,
        ),
    )
}

/** Boş oturum glyph'i: nokta-matris kare. */
@Composable
fun DotGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(72.dp)) {
        val step = 8.dp.toPx()
        val r = 1.2.dp.toPx()
        val c = Damen.Line
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(c, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** i18n.js birebiri (TR/EN). */
object Lang {
    private val tr = mapOf(
        "sessions" to "OTURUMLAR", "new" to "+ YENİ", "model" to "MODEL",
        "thinking" to "DÜŞÜNME", "searchModels" to "model ara…",
        "noModels" to "eşleşen model yok", "send" to "gönder", "stop" to "durdur",
        "placeholder" to "pi'ye yaz…", "attach" to "dosya ekle",
        "fileTooBig" to "dosya çok büyük (20MB)", "removeAttach" to "kaldır",
        "you" to "SEN", "assistant" to "PI", "emptyHint" to "OTURUM YOK — 00",
        "queued" to "kuyrukta", "steer" to "yönlendirme bekliyor",
        "followUp" to "takip bekliyor", "running" to "sürüyor", "secSuffix" to "sn",
        "connecting" to "bağlanıyor…", "booting" to "agent başlatılıyor…",
        "fatal" to "bağlantı hatası", "noSessions" to "oturum yok",
        "cancel" to "iptal", "confirm" to "onayla", "ok" to "tamam",
        "select" to "SEÇİM", "input" to "GİRİŞ", "editor" to "DÜZENLE",
        "confirmKind" to "ONAY", "messages" to "msj", "helpTitle" to "SLASH KOMUTLARI",
        "copied" to "panoya kopyalandı", "noAssistant" to "kopyalanacak yanıt yok",
        "errCopy" to "kopyalanamadı", "jump" to "en alta git",
    )
    private val en = mapOf(
        "sessions" to "SESSIONS", "new" to "+ NEW", "model" to "MODEL",
        "thinking" to "THINKING", "searchModels" to "search models…",
        "noModels" to "no matching model", "send" to "send", "stop" to "stop",
        "placeholder" to "message pi…", "attach" to "attach file",
        "fileTooBig" to "file too big (20MB)", "removeAttach" to "remove",
        "you" to "YOU", "assistant" to "PI", "emptyHint" to "NO SESSION — 00",
        "queued" to "queued", "steer" to "steering pending",
        "followUp" to "follow-up pending", "running" to "running", "secSuffix" to "s",
        "connecting" to "connecting…", "booting" to "booting agent…",
        "fatal" to "connection error", "noSessions" to "no sessions",
        "cancel" to "cancel", "confirm" to "confirm", "ok" to "ok",
        "select" to "SELECT", "input" to "INPUT", "editor" to "EDIT",
        "confirmKind" to "CONFIRM", "messages" to "msg", "helpTitle" to "SLASH COMMANDS",
        "copied" to "copied to clipboard", "noAssistant" to "no reply to copy",
        "errCopy" to "copy failed", "jump" to "scroll to bottom",
    )
    var code: String = "tr"
    fun t(key: String): String = (if (code == "tr") tr else en)[key] ?: en[key] ?: key
}

/** Sol kırmızı şerit (web'de border-left: 2px accent). */
fun Modifier.startBorder(start: Dp, color: Color): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        drawRect(color = color, topLeft = Offset.Zero, size = Size(start.toPx(), size.height))
    },
)

/** fmtTokens birebiri. */
fun fmtTokens(n: Long?): String? {
    if (n == null) return null
    if (n >= 1_000_000) return "%.1fM".format(n / 1_000_000.0)
    if (n >= 1000) return if (n >= 10000) "%dk".format(n / 1000) else "%.1fk".format(n / 1000.0)
    return n.toString()
}
