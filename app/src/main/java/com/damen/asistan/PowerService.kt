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

        fun isEnabled(): Boolean = ref.get() != null

        /** true = menü açıldı, false = servis kapalı (ayar gerekli). */
        fun showPowerDialog(): Boolean {
            val s = ref.get() ?: return false
            return try { s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) } catch (_: Exception) { false }
        }

        /** Android 11+ (API 30+) arka planda sessiz, doğrudan ekran görüntüsü alma */
        fun captureScreen(onComplete: (String?) -> Unit) {
            val s = ref.get()
            if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onComplete(null)
                return
            }
            try {
                s.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    s.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            try {
                                val hwBuffer = result.hardwareBuffer
                                val colorSpace = result.colorSpace
                                val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                val software = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBuffer.close()
                                bmp?.recycle()
                                if (software != null) {
                                    val f = File(s.cacheDir, "auto_shot.png")
                                    FileOutputStream(f).use { fos ->
                                        software.compress(Bitmap.CompressFormat.PNG, 95, fos)
                                    }
                                    software.recycle()
                                    onComplete(f.absolutePath)
                                } else {
                                    onComplete(null)
                                }
                            } catch (_: Exception) {
                                onComplete(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            onComplete(null)
                        }
                    }
                )
            } catch (_: Exception) {
                onComplete(null)
            }
        }
    }
}
