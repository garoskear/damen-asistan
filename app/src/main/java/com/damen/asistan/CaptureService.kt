package com.damen.asistan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Tek kare ekran yakalama. Asistan gizliyken çalışır — çekilen karede asistan UI'ı olmaz.
 * Sonuç: cacheDir/shot.png + CropActivity NEW_TASK ile açılır.
 */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val resultCode = intent?.getIntExtra(EXTRA_CODE, -1) ?: -1
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            }
            if (resultCode == -1 || data == null) { stopSelf(); return START_NOT_STICKY }
            // SIRA KRİTİK (API 34+): önce aktif MediaProjection alınır, SONRA mediaProjection
            // tipli foreground başlatılır. Tersi SecurityException → servis/process ölümü.
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = try {
                mpManager.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                DamenLog.log("SHOT", "getMediaProjection fail: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            startFg()
            scope.launch {
                try { capture(mp) } catch (e: Exception) {
                    DamenLog.log("SHOT", "capture fail: ${e.message}")
                }
                withContext(Dispatchers.Main) { stopSelf() }
            }
        } catch (e: Exception) {
            DamenLog.log("SHOT", "onStartCommand fail: ${e.message}")
            try { stopSelf() } catch (_: Exception) { }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.createNotificationChannel(NotificationChannel(CH, "Ekran yakalama", NotificationManager.IMPORTANCE_MIN))
        } catch (_: Exception) { }
        val n = Notification.Builder(this, CH)
            .setContentTitle("Damen")
            .setContentText("ekran yakalanıyor…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            @Suppress("DEPRECATION") startForeground(ID, n)
        }
    }

    private suspend fun capture(mp: android.media.projection.MediaProjection) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels; val h = metrics.heightPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = mp.createVirtualDisplay(
            "damen-cap", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
        try {
            // Kare otursun diye kısa bekle, son kareyi al
            var bmp: Bitmap? = null
            repeat(10) {
                delay(150)
                try {
                    reader.acquireLatestImage()?.use { img ->
                        val plane = img.planes[0]
                        val rowStride = plane.rowStride
                        val pixelStride = plane.pixelStride
                        val rowW = rowStride / pixelStride
                        val tmp = Bitmap.createBitmap(rowW, h, Bitmap.Config.ARGB_8888)
                        tmp.copyPixelsFromBuffer(plane.buffer)
                        bmp = Bitmap.createBitmap(tmp, 0, 0, w, h)
                        tmp.recycle()
                    }
                } catch (_: Exception) { }
                if (bmp != null) return@repeat
            }
            val out = File(cacheDir, "shot.png")
            bmp?.let {
                FileOutputStream(out).use { fos -> it.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                it.recycle()
                // Asistan CropActivity'yi kendisi açar (sonuç ona döner)
                sendBroadcast(
                    Intent(AssistantActivity.ACTION_SHOT)
                        .putExtra(AssistantActivity.EXTRA_SHOT, out.absolutePath)
                        .setPackage(packageName),
                )
            }
        } finally {
            try { vd.release() } catch (_: Exception) { }
            try { reader.close() } catch (_: Exception) { }
            try { mp.stop() } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "damen-cap"
        private const val ID = 41
    }
}
