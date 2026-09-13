package com.damen.asistan

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * Bağlantı + kısayol config'i.
 *
 * Kural: host/port kodda HARDCODED (ayar ekranı yok). Token + kısayollar
 * düzenlenebilir JSON'dan okunur — önce harici dosya, yoksa app içi, yoksa default:
 *   /sdcard/DamenAsistan/config.json  (elle düzenlenebilir)
 *   <files>/config.json               (uygulamanın kopyası)
 *
 * Token nereden bulunur (Termux):
 *   cat ~/.damen-gw-token  — veya gateway cwd'sindeki .damen-gw-token
 * Bulunan token bu JSON'a yapıştırılır, uygulama restart edilir.
 */
data class Shortcut(
    val id: String,
    val label: String,
    val packageName: String? = null,
    val action: String? = null, // "termux" | "power_dialog" (v2)
)

data class AsistanConfig(
    val token: String = "",
    val shortcuts: List<Shortcut> = listOf(
        Shortcut("termux", "Termux", packageName = "com.termux", action = "termux"),
        Shortcut("power", "Güç", action = "power_dialog"),
    ),
) {
    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8787

        fun wsUrl(token: String) = "ws://$HOST:$PORT/ws?token=$token"
        fun healthUrl() = "http://$HOST:$PORT/api/health"

        private const val EXT_DIR = "DamenAsistan"
        private const val FILE = "config.json"

        fun load(ctx: Context): AsistanConfig {
            // 1) harici (düzenlenebilir)
            try {
                val ext = File(Environment.getExternalStorageDirectory(), "$EXT_DIR/$FILE")
                if (ext.exists()) return parse(ext.readText())
            } catch (_: Exception) { }
            // 2) app içi
            try {
                val inner = File(ctx.filesDir, FILE)
                if (inner.exists()) return parse(inner.readText())
            } catch (_: Exception) { }
            return AsistanConfig()
        }

        fun ensureExternalTemplate(): String {
            return try {
                val dir = File(Environment.getExternalStorageDirectory(), EXT_DIR)
                dir.mkdirs()
                val f = File(dir, FILE)
                if (!f.exists()) f.writeText(defaultJson())
                f.absolutePath
            } catch (e: Exception) {
                "yazılamadı: ${e.message}"
            }
        }

        private fun parse(raw: String): AsistanConfig {
            val o = JSONObject(raw)
            val token = o.optString("token", "")
            val arr = o.optJSONArray("shortcuts")
            val list = mutableListOf<Shortcut>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    list += Shortcut(
                        id = s.optString("id", "s$i"),
                        label = s.optString("label", "s$i"),
                        packageName = s.optString("packageName", "").takeIf { it.isNotBlank() },
                        action = s.optString("action", "").takeIf { it.isNotBlank() },
                    )
                }
            }
            return AsistanConfig(token, list.ifEmpty { AsistanConfig().shortcuts })
        }

        private fun defaultJson() = """{
  "token": "BURAYA_TOKEN",
  "shortcuts": [
    {"id": "termux", "label": "Termux", "packageName": "com.termux", "action": "termux"},
    {"id": "power", "label": "Güç", "action": "power_dialog"}
  ]
}
"""
    }
}
