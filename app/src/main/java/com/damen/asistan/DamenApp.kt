package com.damen.asistan

import android.app.Application
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama seviyesi tanılama günlüğü: /sdcard/Android/data/com.damen.asistan/files/damen.log
 *  (getExternalFilesDir bazı sürümlerde çalışmıyor — yol elle hesaplanır.) */
object DamenLog {
    private var file: File? = null
    fun init() {
        file = try {
            val dir = File(Environment.getExternalStorageDirectory(), "Android/data/com.damen.asistan/files")
            dir.mkdirs()
            File(dir, "damen.log")
        } catch (_: Exception) { null }
    }
    fun log(tag: String, msg: String) {
        val f = file ?: return
        try {
            f.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $tag: $msg\n")
        } catch (_: Exception) { }
    }
}

/** Çökme kaydı: her FATAL, aynı klasördeki crash.txt'e yazılır. */
class DamenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DamenLog.init()
        DamenLog.log("APP", "boot: com.damen.asistan")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val f = try {
                val dir = File(Environment.getExternalStorageDirectory(), "Android/data/com.damen.asistan/files")
                dir.mkdirs()
                File(dir, "crash.txt")
            } catch (_: Exception) { null }
            if (f != null) {
                try {
                    f.appendText(
                        "\n===== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} (thread: ${t.name}) =====\n" +
                            android.util.Log.getStackTraceString(e),
                    )
                } catch (_: Exception) { }
            }
            prev?.uncaughtException(t, e)
        }
    }
}