package com.yyds.screencap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var mpm: MediaProjectionManager
    private var captureService: ScreenCaptureService? = null
    private var isBound = false

    private val resultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val ip = findViewById<EditText>(R.id.et_server_ip).text.toString().trim()
            val port = findViewById<EditText>(R.id.et_server_port).text.toString().trim().toIntOrNull() ?: 5000
            val interval = findViewById<EditText>(R.id.et_interval).text.toString().trim().toLongOrNull() ?: 1L

            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                putExtra("server_ip", ip)
                putExtra("server_port", port)
                putExtra("interval_sec", interval)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateUi(true)
            Toast.makeText(this, "截屏服务已启动", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "用户取消授权", Toast.LENGTH_SHORT).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (checkNotificationPermission()) {
                requestScreenCapture()
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopCaptureService()
        }

        // 绑定服务以获取状态
        bindService(Intent(this, ScreenCaptureService::class.java), connection, Context.BIND_AUTO_CREATE)

        updateUi(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi(isServiceRunning())
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }
        return true
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "需要通知权限以运行后台服务", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestScreenCapture() {
        val intent = mpm.createScreenCaptureIntent()
        resultLauncher.launch(intent)
    }

    private fun stopCaptureService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        updateUi(false)
        Toast.makeText(this, "截屏服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(): Boolean {
        captureService?.let {
            return it.isRunning()
        }
        return false
    }

    private fun updateUi(running: Boolean) {
        findViewById<Button>(R.id.btn_start).isEnabled = !running
        findViewById<Button>(R.id.btn_stop).isEnabled = running
        findViewById<EditText>(R.id.et_server_ip).isEnabled = !running
        findViewById<EditText>(R.id.et_server_port).isEnabled = !running
        findViewById<EditText>(R.id.et_interval).isEnabled = !running
        val tv = findViewById<TextView>(R.id.tv_status)
        tv.text = if (running) "状态: 运行中" else "状态: 已停止"
    }
}
