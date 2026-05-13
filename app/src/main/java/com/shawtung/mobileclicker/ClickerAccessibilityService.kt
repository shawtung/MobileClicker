package com.shawtung.mobileclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService used to dispatch tap gestures on screen.
 * This replaces the need for Shizuku's `input tap` command.
 *
 * Requires the user to manually enable this service in
 * Settings → Accessibility → MobileClicker.
 */
class ClickerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickerA11y"

        /** Singleton instance, non-null when service is connected */
        var instance: ClickerAccessibilityService? = null
            private set

        /** Check if the accessibility service is active and ready */
        val isActive: Boolean get() = instance != null
    }

    private var testReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")

        // Register test broadcast receiver
        testReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.getStringExtra("action") ?: "home"
                Log.d(TAG, "Test broadcast received: action=$action")
                when (action) {
                    "home" -> Log.d(TAG, "goHome result: ${goHome()}")
                    "back" -> Log.d(TAG, "goBack result: ${goBack()}")
                    "ntfy" -> {
                        // Test ntfy from device
                        Thread {
                            try {
                                val topic = "f5b4ac88-ea86-4788-bf90-4dc5ceca3f2c"
                                val url = java.net.URL("https://ntfy.sh/$topic")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("Title", "MobileClicker")
                                conn.connectTimeout = 10000
                                conn.readTimeout = 10000
                                conn.doOutput = true
                                conn.outputStream.use { it.write("Test from device".toByteArray()) }
                                val code = conn.responseCode
                                conn.disconnect()
                                Log.d(TAG, "ntfy test result: HTTP $code")
                            } catch (e: Exception) {
                                Log.d(TAG, "ntfy test error: ${e.message}")
                            }
                        }.start()
                    }
                }
            }
        }
        registerReceiver(testReceiver, IntentFilter("com.shawtung.mobileclicker.TEST_A11Y"),
            Context.RECEIVER_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle any events
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        testReceiver?.let { unregisterReceiver(it) }
        testReceiver = null
        instance = null
        Log.d(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    /**
     * Dispatch a tap gesture at the given screen coordinates.
     * Uses AccessibilityService's dispatchGesture API (Android 7.0+).
     *
     * @param x Screen X coordinate
     * @param y Screen Y coordinate
     * @param callback Optional callback for gesture completion
     * @return true if the gesture was dispatched, false if failed
     */
    fun tap(x: Float, y: Float, callback: GestureResultCallback? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "dispatchGesture requires API 24+")
            return false
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,    // startTime: immediate
            50L    // duration: 50ms tap
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture, callback ?: object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
    }

    /**
     * Dispatch a swipe gesture from one point to another.
     */
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, null)
    }

    /** Simulate pressing the Home button */
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Simulate pressing the Back button */
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
