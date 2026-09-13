package com.damen.asistan

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/** Pill "Güç" tuşu: sistem güç menüsünü açar; Android 11+ arka planda ekran görüntüsü alır. */
class PowerService : AccessibilityService() {

    override fun onServiceConnected() {
        ref = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* yalnız global action için */ }

    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ref.clear()
        return super.onUnbind(intent)
    }

    companion object {
        private var ref = WeakReference<PowerService>(null)
        private var capturing = false
        private val lock = Any()

        fun isEnabled(): Boolean = ref.get() != null

        /** true = menü açıldı, false = servis kapalı (ayar gerekli). */
        fun showPowerDialog(): Boolean {
            val s = ref.get() ?: return false
            return try { s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) } catch (_: Exception) { false }
        }

        /**
         * Android 11+ (API 30+) arka planda sessiz ekran görüntüsü.
         *
         * KRİTİK: hardware buffer'dan software bitmap'e copy, RENDER THREAD'e bağlıdır —
         * arka plan thread'inde yapılırsa bazı cihazlarda native SIGSEGV çökmesi olur
         * (JVM yakalayamaz, crash.txt yazılmaz). Bu yüzden:
         *   - wrap + copy → mainExecutor üzerinde (dokümante desen)
         *   - PNG sıkıştırma + dosya yazma → arka plan thread'inde
         * Ayrıca eşzamanlı iki takeScreenshot sistemi çökertebilir — `capturing` kilidi var.
         */
        fun captureScreen(onComplete: (String?) -> Unit) {
            val s = ref.get()
            if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onComplete(null)
                return
            }
            val started = synchronized(lock) {
                if (capturing) false else { capturing = true; true }
            }
            if (!started) { onComplete(null); return }
            val finish = { p: String? -> synchronized(lock) { capturing = false }; onComplete(p) }

            try {
                s.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    s.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            // COPY MAIN THREAD'DE (render-uyumlu) — native crash riski yok
                            var software: Bitmap? = null
                            try {
                                val hw = result.hardwareBuffer
                                if (hw != null) {
                                    val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                                    software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                    try { wrapped?.recycle() } catch (_: Exception) { }
                                    try { hw.close() } catch (_: Exception) { }
                                }
                            } catch (e: Exception) {
                                DamenLog.log("SHOT", "copy fail: ${e.message}")
                                software = null
                            }
                            val soft = software
                            if (soft != null && soft.width > 0 && soft.height > 0) {
                                val path = File(s.cacheDir, "auto_shot.png").absolutePath
                                Thread({
                                    try {
                                        val tmp = File(s.cacheDir, "auto_shot.tmp")
                                        val out = File(path)
                                        FileOutputStream(tmp).use { fos ->
                                            soft.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                                        }
                                        try { soft.recycle() } catch (_: Exception) { }
                                        try { out.delete() } catch (_: Exception) { }
                                        val ok = tmp.renameTo(out) || out.exists()
                                        DamenLog.log("SHOT", "capture saved: $path ($ok)")
                                        finish(if (ok) path else null)
                                    } catch (e: Exception) {
                                        DamenLog.log("SHOT", "save fail: ${e.message}")
                                        finish(null)
                                    }
                                }, "damen-shot-write").start()
                            } else {
                                DamenLog.log("SHOT", "copy null: bitmap yok")
                                finish(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            DamenLog.log("SHOT", "takeScreenshot onFailure: $errorCode")
                            finish(null)
                        }
                    },
                )
            } catch (e: Exception) {
                DamenLog.log("SHOT", "takeScreenshot exception: ${e.message}")
                finish(null)
            }
        }
    }
}