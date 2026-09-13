package com.damen.asistan

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama seviyesi tanılama günlüğü: /sdcard/Android/data/com.damen.asistan/files/damen.log */
object DamenLog {
    private var file: File? = null
    fun init(dir: File) { file = File(dir, "damen.log") }
    fun log(tag: String, msg: String) {
        val f = file ?: return
        try {
            f.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $tag: $msg\n")
        } catch (_: Exception) { }
    }
}

/** Çökme kaydı: her FATAL, crash.txt'e yazılır. */
class DamenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { DamenLog.init(getExternalFilesDir(null) ?: filesDir) } catch (_: Exception) { }
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
