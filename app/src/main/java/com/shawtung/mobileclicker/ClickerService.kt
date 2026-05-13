package com.shawtung.mobileclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import rikka.shizuku.Shizuku
import kotlin.random.Random

class ClickerService : Service() {

    companion object {
        var logCallback: ((String) -> Unit)? = null
        var statusCallback: ((String) -> Unit)? = null
        private const val CHANNEL_ID = "mobileclicker_channel"

        // Game config
        const val REVENUE_TARGET = 1500
        const val SCAN_INTERVAL_MS = 3000L
        const val GAMEPLAY_SLOW_MS = 8000L
        const val GAMEPLAY_FAST_MS = 2000L
        const val LOG_MAX_BYTES = 500 * 1024L

        // Exit icon relative position within game content area
        // Empirically calibrated: successful hits at rel ~(0.054-0.059, 0.039-0.041)
        const val EXIT_ICON_REL_X = 0.057
        const val EXIT_ICON_REL_Y = 0.039

        // Start button fallback relative position
        const val START_BTN_REL_X = 0.845  // 2365/2800
        const val START_BTN_REL_Y = 0.938  // 1200/1280

        // ntfy.sh push
        val NTFY_TOPIC = BuildConfig.NTFY_TOPIC
    }

    /**
     * Represents a rectangular region on screen (window bounds or content area).
     */
    data class GameWindow(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val aspectRatio get() = width.toDouble() / height

        /** Convert a relative X (0.0~1.0) to absolute screen X */
        fun absX(relX: Double) = left + (width * relX).toInt()
        /** Convert a relative Y (0.0~1.0) to absolute screen Y */
        fun absY(relY: Double) = top + (height * relY).toInt()
        /** Convert absolute X to relative X within this region (0.0~1.0) */
        fun relX(absX: Int) = (absX - left).toDouble() / width
        /** Convert absolute Y to relative Y within this region (0.0~1.0) */
        fun relY(absY: Int) = (absY - top).toDouble() / height
    }

    /** Game window bounds from dumpsys, updated each scan */
    private var gameWindow: GameWindow? = null
    /** Target app package name, used for window bounds detection via dumpsys */
    private var targetPackage: String = ""

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var paused = false
    private var loopCount = 0
    private var energy = -1
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var bubbleView: View? = null

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // State machine
    private enum class GameState { MAP, STAGE_SELECT, GAMEPLAY, SETTLEMENT, UNKNOWN }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Prevent duplicate starts
            if (running) {
                log("Service already running, ignoring duplicate start")
                return START_NOT_STICKY
            }

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MobileClicker")
                .setContentText("Running...")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }

            val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("data")
            }

            log("Service started: resultCode=$resultCode data=$data")

            targetPackage = intent?.getStringExtra("targetPackage") ?: ""
            log("Target package: ${targetPackage.ifEmpty { "(not set)" }}")

            if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                log("Error: No MediaProjection data (resultCode=$resultCode)")
                stopSelf()
                return START_NOT_STICKY
            }

            // Get screen metrics
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
            log("Screen: ${screenWidth}x${screenHeight} density=$screenDensity")

            // Start MediaProjection
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)

            // Android 14+ requires registering a callback before createVirtualDisplay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        log("MediaProjection stopped by system")
                        running = false
                        // Clean up safely — projection was killed (e.g. single-app mode + overlay tap)
                        try { virtualDisplay?.release() } catch (_: Exception) {}
                        try { imageReader?.close() } catch (_: Exception) {}
                        virtualDisplay = null
                        imageReader = null
                    }
                }, handler)
            }

            // Capture in actual display orientation (portrait or landscape as-is)
            // This ensures screenshot coordinates match input tap & SurfaceFlinger coordinates
            val captureW = screenWidth
            val captureH = screenHeight
            log("Capture size: ${captureW}x${captureH} (matching display orientation)")

            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)

            handlerThread = HandlerThread("ClickerThread").also { it.start() }
            handler = Handler(handlerThread!!.looper)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MobileClicker",
                captureW, captureH, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            running = true
            showBubble()
            handler?.postDelayed({ scanLoop() }, 1000)
            log("Started. Waiting for game...")

            return START_NOT_STICKY
        } catch (t: Throwable) {
            log("FATAL onStartCommand: ${t.javaClass.simpleName}: ${t.message}")
            log(android.util.Log.getStackTraceString(t))
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private var nullImageCount = 0

    private fun scanLoop() {
        if (!running) return
        if (paused) {
            handler?.postDelayed({ scanLoop() }, 1000)
            return
        }

        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                nullImageCount++
                if (nullImageCount % 10 == 1) {
                    log("[SCAN] No image available (count=$nullImageCount)")
                }
                handler?.postDelayed({ scanLoop() }, 1000)
                return
            }
            nullImageCount = 0

            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap == null) {
                handler?.postDelayed({ scanLoop() }, SCAN_INTERVAL_MS)
                return
            }

            val w = bitmap.width
            val h = bitmap.height
            log("[SCAN] ${w}x${h}")

            // Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    handleOcrResult(visionText, w, h)
                    bitmap.recycle()
                }
                .addOnFailureListener { e ->
                    log("[ERROR] OCR failed: ${e.message}")
                    bitmap.recycle()
                    scheduleNext(SCAN_INTERVAL_MS)
                }
        } catch (e: Exception) {
            // MediaProjection may have been stopped (e.g. single-app mode + overlay tap)
            log("[SCAN] Error (projection stopped?): ${e.message}")
            if (!running) return
            handler?.postDelayed({ scanLoop() }, SCAN_INTERVAL_MS)
        }
    }

    private fun handleOcrResult(visionText: Text, imgW: Int, imgH: Int) {
        val items = mutableListOf<OcrItem>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = (box.left + box.right) / 2
                val cy = (box.top + box.bottom) / 2
                items.add(OcrItem(line.text, cx, cy))
            }
        }

        // Query precise game window bounds via dumpsys
        if (targetPackage.isNotEmpty()) {
            gameWindow = queryWindowBounds(targetPackage)
        }
        if (gameWindow != null) {
            val gw = gameWindow!!
            log("  [WINDOW] (${gw.left},${gw.top})-(${gw.right},${gw.bottom}) ${gw.width}x${gw.height} aspect=%.3f".format(gw.aspectRatio))
        } else {
            log("  [WINDOW] NOT DETECTED (targetPackage=${targetPackage.ifEmpty { "NOT SET" }})")
        }

        // Debug: dump all OCR items with positions
        log("  [OCR] ${items.size} items:")
        for ((i, item) in items.withIndex()) {
            val gw = gameWindow
            val relInfo = if (gw != null && gw.width > 0 && gw.height > 0) {
                val rx = gw.relX(item.cx)
                val ry = gw.relY(item.cy)
                " rel=(%.3f, %.3f)".format(rx, ry)
            } else ""
            log("    [$i] (${item.cx},${item.cy})$relInfo \"${item.text}\"")
        }

        val state = detectState(items)
        log("  [STATE] $state")
        statusCallback?.invoke("$state | Loop #$loopCount | Energy: $energy")

        val delay = when (state) {
            GameState.MAP -> {
                handleMap(items)
                SCAN_INTERVAL_MS
            }
            GameState.STAGE_SELECT -> {
                handleStageSelect(items)
                SCAN_INTERVAL_MS
            }
            GameState.GAMEPLAY -> {
                handleGameplay(items)
                lastGameplayDelay
            }
            GameState.SETTLEMENT -> {
                handleSettlement(items)
                SCAN_INTERVAL_MS
            }
            GameState.UNKNOWN -> {
                val preview = items.take(5).joinToString(", ") { it.text }
                log("  [UNKNOWN] $preview")
                SCAN_INTERVAL_MS
            }
        }

        if (running) scheduleNext(delay)
    }

    private fun detectState(items: List<OcrItem>): GameState {
        // Settlement: has "collect" button
        if (items.any { "领取" in it.text }) return GameState.SETTLEMENT

        // Stage select
        if (items.any { "开始营业" in it.text || "经营目标" in it.text ||
                    "经营事件" in it.text || "辅助雇员" in it.text || "累计获得" in it.text }) {
            return GameState.STAGE_SELECT
        }

        // Gameplay: timer pattern
        val hasTimer = items.any { Regex("\\d+分\\d+秒").containsMatchIn(it.text) }
        if (hasTimer) return GameState.GAMEPLAY

        // Gameplay fallback: X/1,500 near top of game window (top 25%)
        val gw = gameWindow
        if (items.any { item ->
                Regex("[\\d,]+/[\\d,]*1.500").containsMatchIn(item.text) &&
                (gw == null || gw.relY(item.cy) < 0.25)
            }) {
            return GameState.GAMEPLAY
        }

        // Map
        if (items.any { "店长特供" in it.text }) return GameState.MAP

        return GameState.UNKNOWN
    }

    private fun handleMap(items: List<OcrItem>) {
        val pos = items.find { "店长特供" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "store special")
        }
    }

    private fun handleStageSelect(items: List<OcrItem>) {
        // Check energy
        parseRatio(items, 500)?.let { (cur, total) ->
            energy = cur
            log("  [ENERGY] $cur/$total")
            if (cur <= 0) {
                log("[STOP] Energy depleted!")
                running = false
                statusCallback?.invoke("Stopped: no energy")
                notify("Energy depleted after $loopCount loops")
                killGame()
                stopSelf()
                return
            }
        }

        val pos = items.find { "开始营业" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "start business")
        } else {
            val gw = gameWindow
            if (gw != null) {
                tap(gw.absX(START_BTN_REL_X), gw.absY(START_BTN_REL_Y), "start business (fallback/window)")
            } else {
                log("  [ERROR] Cannot tap start button: game window not detected")
            }
        }
    }

    private var lastGameplayDelay = GAMEPLAY_SLOW_MS

    private fun handleGameplay(items: List<OcrItem>) {
        val revenue = parseRatio(items, 1500)
        if (revenue != null) {
            val (cur, total) = revenue
            if (cur >= REVENUE_TARGET) {
                log("  [REVENUE] $cur/$total >= $REVENUE_TARGET, exiting!")
                val gw = gameWindow
                if (gw != null) {
                    // Small jitter for exit icon — it's a tiny target in small windows
                    tap(gw.absX(EXIT_ICON_REL_X), gw.absY(EXIT_ICON_REL_Y), "exit icon", jitter = 5)
                } else {
                    log("  [ERROR] Cannot tap exit icon: game window not detected")
                }
                // Use fast interval for exit retries (button is small, might miss)
                lastGameplayDelay = GAMEPLAY_FAST_MS
            } else {
                // Adaptive: slow when far, fast when close
                val ratio = cur.toDouble() / REVENUE_TARGET
                lastGameplayDelay = if (ratio >= 0.7) GAMEPLAY_FAST_MS else GAMEPLAY_SLOW_MS
                log("  [REVENUE] $cur/$total (target: $REVENUE_TARGET, next: ${lastGameplayDelay}ms)")
            }
        } else {
            log("  [GAMEPLAY] Waiting...")
            lastGameplayDelay = GAMEPLAY_SLOW_MS
        }
    }

    private fun handleSettlement(items: List<OcrItem>) {
        // Check energy
        parseRatio(items, 500)?.let { (cur, total) ->
            energy = cur
            log("  [ENERGY] $cur/$total")
            if (cur <= 0) {
                log("[STOP] Energy depleted!")
                running = false
                statusCallback?.invoke("Stopped: no energy")
                notify("Energy depleted after $loopCount loops")
                killGame()
                stopSelf()
                return
            }
        }

        val pos = items.find { "领取" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "collect")
        }

        loopCount++
        log("  [LOOP] Completed loop #$loopCount")
    }

    private fun parseRatio(items: List<OcrItem>, expectedTotal: Int): Pair<Int, Int>? {
        val pattern = Regex("([\\d,]+)\\s*/\\s*([\\d,]+)")
        for (item in items) {
            val match = pattern.find(item.text) ?: continue
            val cur = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            val total = match.groupValues[2].replace(",", "").toIntOrNull() ?: continue
            // Match by expected total (fuzzy: within 10%)
            if (total in (expectedTotal * 9 / 10)..(expectedTotal * 11 / 10)) {
                return cur to total
            }
        }
        return null
    }

    private fun randomDelay(baseMs: Long): Long {
        val jitter = (baseMs * 0.3).toLong()
        return baseMs + Random.nextLong(-jitter, jitter + 1)
    }

    private fun tap(x: Int, y: Int, label: String, jitter: Int = 15) {
        val rx = x + Random.nextInt(-jitter, jitter + 1)
        val ry = y + Random.nextInt(-jitter, jitter + 1)
        val gw = gameWindow
        val relInfo = if (gw != null && gw.width > 0 && gw.height > 0) {
            " rel=(%.3f, %.3f)".format(gw.relX(rx), gw.relY(ry))
        } else ""
        log("  [TAP] ($rx, $ry)$relInfo $label")
        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", "input tap $rx $ry"), null, null)
            process.waitFor()
        } catch (e: Exception) {
            log("  [TAP ERROR] ${e.message}")
        }
        // Random post-tap delay 200-600ms
        Thread.sleep(Random.nextLong(200, 600))
    }

    private fun killGame() {
        if (targetPackage.isEmpty()) return
        val gw = gameWindow
        val isFullscreen = gw != null &&
                gw.width >= screenWidth - 20 && gw.height >= screenHeight - 20
        if (isFullscreen) {
            // Fullscreen: press HOME to return to launcher (game stays in background)
            log("[KILL] Fullscreen mode — sending HOME key")
            try {
                val process = Shizuku.newProcess(
                    arrayOf("sh", "-c", "input keyevent 3"), null, null
                )
                process.waitFor()
            } catch (e: Exception) {
                log("[KILL ERROR] ${e.message}")
            }
        } else {
            // Freeform/small window: force-stop to close the window
            log("[KILL] Freeform mode — force-stopping $targetPackage")
            try {
                val process = Shizuku.newProcess(
                    arrayOf("sh", "-c", "am force-stop $targetPackage"), null, null
                )
                process.waitFor()
            } catch (e: Exception) {
                log("[KILL ERROR] ${e.message}")
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop padding if any
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }

    private fun scheduleNext(delayMs: Long) {
        handler?.postDelayed({ scanLoop() }, randomDelay(delayMs))
    }

    private fun notify(msg: String) {
        if (NTFY_TOPIC.isBlank()) {
            log("  [NTFY] Skipped (no topic configured)")
            return
        }
        Thread {
            try {
                val url = java.net.URL("https://ntfy.sh/$NTFY_TOPIC")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Title", "MobileClicker")
                conn.doOutput = true
                conn.outputStream.use { it.write(msg.toByteArray()) }
                conn.responseCode
                conn.disconnect()
                log("  [NTFY] Sent: $msg")
            } catch (e: Exception) {
                log("  [NTFY ERROR] ${e.message}")
            }
        }.start()
    }

    private fun log(msg: String) {
        android.util.Log.d("MobileClicker", msg)
        logCallback?.invoke(msg)
        writeLogFile(msg)
    }

    private fun writeLogFile(msg: String) {
        try {
            val f = java.io.File(getExternalFilesDir(null), "mobileclicker.log")
            if (f.length() > LOG_MAX_BYTES) {
                // Keep last 200KB
                val tail = f.readText().takeLast(200 * 1024)
                f.writeText(tail)
            }
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n")
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MobileClicker", NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running = false
        removeBubble()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        handlerThread?.quitSafely()
        super.onDestroy()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            log("[BUBBLE] No overlay permission")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "\u25B6"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC4CAF50.toInt())
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        var downX = 0f
        var downY = 0f
        var downParamX = 0
        var downParamY = 0
        var moved = false

        tv.setOnTouchListener { _, event ->
            try {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downParamX = params.x
                        downParamY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx * dx + dy * dy > 25) moved = true
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        wm.updateViewLayout(tv, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            paused = !paused
                            tv.text = if (paused) "\u23F8" else "\u25B6"
                            tv.setBackgroundColor(
                                if (paused) 0xCCF44336.toInt() else 0xCC4CAF50.toInt()
                            )
                            log(if (paused) "[PAUSED]" else "[RESUMED]")
                            statusCallback?.invoke(if (paused) "Paused" else "Running")
                        }
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                log("[BUBBLE] Touch error: ${e.message}")
                true
            }
        }

        wm.addView(tv, params)
        bubbleView = tv
        log("[BUBBLE] Shown")
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            bubbleView = null
        }
    }

    data class OcrItem(val text: String, val cx: Int, val cy: Int)

    /**
     * Query precise window bounds of the target app in PHYSICAL DISPLAY coordinates.
     *
     * Strategy:
     * 1. Primary: parse `displayFrame` from `dumpsys SurfaceFlinger` (guaranteed physical pixels)
     * 2. Fallback: parse `frame=` from `dumpsys window windows` (may be in virtual coordinate space
     *    on some OEMs with compatibility scaling — logged for debugging)
     *
     * If multiple surfaces/windows exist for the package, returns the largest one.
     */
    private fun queryWindowBounds(packageName: String): GameWindow? {
        // Try SurfaceFlinger first (gives physical display coordinates)
        val sfResult = queryFromSurfaceFlinger(packageName)
        if (sfResult != null) return sfResult

        // Fallback to window manager (may need coordinate conversion on some devices)
        log("  [WINDOW] SurfaceFlinger failed, trying window manager...")
        return queryFromWindowManager(packageName)
    }

    /**
     * Parse displayFrame from SurfaceFlinger output.
     * Format: `displayFrame=[left top right bottom]`
     */
    private fun queryFromSurfaceFlinger(packageName: String): GameWindow? {
        try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "dumpsys SurfaceFlinger"), null, null
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Find displayFrame lines near our package name
            // SurfaceFlinger output has surface entries with package name and displayFrame nearby
            val displayFramePattern = Regex("displayFrame=\\[(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)]")
            val frames = mutableListOf<GameWindow>()

            val lines = output.lines()
            var inTargetSection = false

            for (line in lines) {
                // Detect sections related to our package
                if (packageName in line) {
                    inTargetSection = true
                    continue
                }
                // Look for displayFrame within ~30 lines of package name reference
                if (inTargetSection) {
                    val match = displayFramePattern.find(line)
                    if (match != null) {
                        val left = match.groupValues[1].toInt()
                        val top = match.groupValues[2].toInt()
                        val right = match.groupValues[3].toInt()
                        val bottom = match.groupValues[4].toInt()
                        val w = right - left
                        val h = bottom - top
                        if (w > 50 && h > 50) {
                            log("  [SF] displayFrame=[$left $top $right $bottom] ${w}x${h}")
                            frames.add(GameWindow(left, top, right, bottom))
                        }
                        inTargetSection = false
                    }
                    // Don't search too far from the package reference
                    if (line.trimStart().startsWith("visible reason=") ||
                        line.trimStart().startsWith("RequestedLayerState{")) {
                        inTargetSection = false
                    }
                }
            }

            if (frames.isEmpty()) {
                log("  [SF] No displayFrame found for: $packageName")
                return null
            }

            val result = frames.maxByOrNull { it.width.toLong() * it.height }!!
            log("  [SF] Selected: (${result.left},${result.top})-(${result.right},${result.bottom}) ${result.width}x${result.height}")
            return result
        } catch (e: Exception) {
            log("  [SF] query failed: ${e.message}")
        }
        return null
    }

    /**
     * Fallback: parse frame from `dumpsys window windows`.
     * NOTE: On some OEMs (Honor/Huawei) with freeform + compatibility scaling,
     * these coordinates are in a VIRTUAL space, not physical display pixels.
     * Results are logged for debugging.
     */
    private fun queryFromWindowManager(packageName: String): GameWindow? {
        try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "dumpsys window windows"), null, null
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val framePattern = Regex("(?:mFrame|frame)=\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
            val scalePattern = Regex("mGlobalScale=([\\d.]+)")
            val invScalePattern = Regex("mInvGlobalScale=([\\d.]+)")
            val windows = mutableListOf<GameWindow>()
            val lines = output.lines()
            var inTargetWindow = false
            var currentWindowTitle = ""
            var foundFrame = false
            var globalScale = 1.0
            var invGlobalScale = 1.0

            for (line in lines) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("Window #")) {
                    inTargetWindow = packageName in line
                    if (inTargetWindow) {
                        currentWindowTitle = trimmed
                        foundFrame = false
                        globalScale = 1.0
                        invGlobalScale = 1.0
                    }
                    continue
                }
                if (!inTargetWindow) continue

                scalePattern.find(line)?.let {
                    globalScale = it.groupValues[1].toDoubleOrNull() ?: 1.0
                }
                invScalePattern.find(line)?.let {
                    invGlobalScale = it.groupValues[1].toDoubleOrNull() ?: 1.0
                }

                if (!foundFrame && ("mFrame=" in line || "frame=" in line)) {
                    val match = framePattern.find(line)
                    if (match != null) {
                        val left = match.groupValues[1].toInt()
                        val top = match.groupValues[2].toInt()
                        val right = match.groupValues[3].toInt()
                        val bottom = match.groupValues[4].toInt()
                        val w = right - left
                        val h = bottom - top
                        log("  [WM] $currentWindowTitle")
                        log("    frame=[$left,$top][$right,$bottom] ${w}x${h} scale=$globalScale invScale=$invGlobalScale")
                        log("    ⚠️ WARNING: These coords may be in virtual space, not physical display!")
                        if (w > 0 && h > 0) {
                            windows.add(GameWindow(left, top, right, bottom))
                        }
                        foundFrame = true
                    }
                }
            }

            if (windows.isEmpty()) {
                log("  [WM] No windows found for package: $packageName")
            }
            return windows.maxByOrNull { it.width.toLong() * it.height }
        } catch (e: Exception) {
            log("  [WINDOW] WM query failed: ${e.message}")
        }
        return null
    }
}
