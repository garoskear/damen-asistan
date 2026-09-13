package com.damen.asistan

import android.app.Application
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama seviyesi tanılama günlüğü. Yol sırası:
 *  1) getExternalFilesDir(null)/damen.log  (resmi, izin gerektirmez)
 *  2) /sdcard/Android/data/com.damen.asistan/files/damen.log (elle — yedek)
 */
object DamenLog {
    @Volatile private var file: File? = null
    private var bootLogged = false

    fun init(app: Application) {
        val f = try { app.getExternalFilesDir(null)?.let { File(it, "damen.log") } } catch (_: Exception) { null }
            ?: try {
                val dir = File(Environment.getExternalStorageDirectory(), "Android/data/com.damen.asistan/files")
                dir.mkdirs()
                File(dir, "damen.log")
            } catch (_: Exception) { null }
        file = f
        log("APP", "boot: com.damen.asistan -> ${f?.absolutePath}")
    }

    fun log(tag: String, msg: String) {
        val f = file ?: return
        try {
            f.appendText("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $tag: $msg\n")
        } catch (_: Exception) { }
    }
}

/** Çökme kaydı: her FATAL, aynı klasördeki crash.txt'e yazılır. (Native SIGSEGV yakalanamaz —
 *  o yüzden screenshot çekimindeki çökme native olabilir, aşağıda işaretlenmiştir.) */
class DamenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DamenLog.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val dir = try { getExternalFilesDir(null) } catch (_: Exception) { null }
                ?: try { File(Environment.getExternalStorageDirectory(), "Android/data/com.damen.asistan/files").apply { mkdirs() } } catch (_: Exception) { null }
            if (dir != null) {
                try {
                    File(dir, "crash.txt").appendText(
                        "\n===== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} (thread: ${t.name}) =====\n" +
                            android.util.Log.getStackTraceString(e),
                    )
                } catch (_: Exception) { }
            }
            prev?.uncaughtException(t, e)
        }
    }
}