package com.yyds.screencap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "screen_capture_channel"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var serverIp = ""
    private var serverPort = 5000
    private var intervalMs = 1000L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isActive = AtomicBoolean(false)
    private var captureRunnable: Runnable? = null

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        serverIp = intent.getStringExtra("server_ip") ?: ""
        serverPort = intent.getIntExtra("server_port", 5000)
        intervalMs = (intent.getLongExtra("interval_sec", 1L) * 1000).coerceAtLeast(500L)

        val code = intent.getIntExtra("code", -1)
        val data = intent.getParcelableExtra("data", Intent::class.java)

        if (code == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(code, data)

        startCapture()

        return START_STICKY
    }

    private fun startCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isActive.set(true)
        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        captureRunnable = Runnable {
            if (!isActive.get()) return@Runnable
            captureAndSend()
            mainHandler.postDelayed(captureRunnable!!, intervalMs)
        }
        mainHandler.post(captureRunnable!!)
    }

    private fun captureAndSend() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        executor.execute {
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // 裁剪掉 padding
                val crop = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                } else {
                    bitmap
                }

                val baos = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val imageData = baos.toByteArray()

                sendToServer(imageData)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }
    }

    private fun sendToServer(imageData: ByteArray) {
        try {
            val url = URL("http://$serverIp:$serverPort/upload")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("Connection", "close")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            conn.outputStream.use { os ->
                os.write(imageData)
                os.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                android.util.Log.w("ScreenCapture", "Server returned $responseCode")
            }
            conn.disconnect()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapture", "Send failed: ${e.message}")
        }
    }

    fun isRunning(): Boolean = isActive.get()

    override fun onDestroy() {
        isActive.set(false)
        captureRunnable?.let { mainHandler.removeCallbacks(it) }
        executor.shutdownNow()

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("投屏助手")
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
