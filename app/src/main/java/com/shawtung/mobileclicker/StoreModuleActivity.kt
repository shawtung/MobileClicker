package com.shawtung.mobileclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class StoreModuleActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private var targetPackage: String = ""

    private val shizukuPermissionCode = 100

    private fun fileLog(msg: String) {
        Log.d("MobileClicker", msg)
        try {
            val f = java.io.File(getExternalFilesDir(null), "mobileclicker.log")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} [UI] $msg\n")
        } catch (_: Exception) {}
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileLog("MediaProjection result: code=${result.resultCode} data=${result.data}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ClickerService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
                putExtra("targetPackage", targetPackage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = "Status: Running"
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        targetPackage = intent?.getStringExtra("targetPackage") ?: ""
        if (targetPackage.isNotEmpty()) {
            fileLog("Target package: $targetPackage")
        }

        startBtn.setOnClickListener { onStartClicker() }
        stopBtn.setOnClickListener { onStopClicker() }
        stopBtn.isEnabled = false


        ClickerService.statusCallback = { status ->
            runOnUiThread {
                statusText.text = "Status: $status"
                if (status == "Stopped") {
                    startBtn.isEnabled = true
                    stopBtn.isEnabled = false
                }
            }
        }

        requestNotificationPermissionIfNeeded()
        checkShizuku()
    }

    override fun onResume() {
        super.onResume()
        checkShizuku()
        updateA11yStatus()
    }

    private fun updateA11yStatus() {
        val enabled = ClickerAccessibilityService.isActive
        val text = statusText.text.toString()
        if (text.contains("A11y")) return
        if (enabled) {
            statusText.text = "$text | A11y: ✓"
        } else {
            // Render "(请开启无障碍)" as a tappable link that jumps to the system
            // Accessibility settings, so the user can enable the service in one tap.
            val sb = SpannableStringBuilder("$text | A11y: ✗ ")
            val link = "(请开启无障碍)"
            val start = sb.length
            sb.append(link)
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = openAccessibilitySettings()
            }, start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            statusText.text = sb
            statusText.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }
    }

    private fun checkShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    statusText.text = "Status: Shizuku OK. Ready."
                } else {
                    Shizuku.requestPermission(shizukuPermissionCode)
                    statusText.text = "Status: Requesting Shizuku permission..."
                }
            } else {
                statusText.text = "Status: Shizuku not running!"
            }
        } catch (e: Exception) {
            statusText.text = "Status: Shizuku error: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == shizukuPermissionCode) {
            checkShizuku()
        }
    }

    private fun onStartClicker() {
        fileLog("onStartClicker called")
        if (!Settings.canDrawOverlays(this)) {
            fileLog("Requesting overlay permission")
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        // Request screen capture
        fileLog("Requesting screen capture")
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun onStopClicker() {
        stopService(Intent(this, ClickerService::class.java))
        statusText.text = "Status: Stopped"
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        ClickerService.statusCallback = null
    }
}
