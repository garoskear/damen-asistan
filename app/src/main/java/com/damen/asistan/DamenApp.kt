package com.damen.asistan

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Çökme kaydı: her FATAL, /sdcard/Android/data/com.damen.asistan/files/crash.txt'e yazılır. */
class DamenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash.txt").appendText(
                    "\n===== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} (thread: ${t.name}) =====\n" +
                        android.util.Log.getStackTraceString(e),
                )
            } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }
}
