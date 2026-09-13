package com.damen.asistan

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/** Pill "Güç" tuşu: sistem güç menüsünü açar (kilitle/kapat/yeniden başlat/acil durum). */
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
    }
}
