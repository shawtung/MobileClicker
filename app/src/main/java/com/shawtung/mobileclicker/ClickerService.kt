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

    /** Game window bounds, updated each scan via SurfaceFlinger */
    private var gameWindow: GameWindow? = null
    /** Target app package name */
    private var targetPackage: String = ""
    /** Whether Shizuku is available (checked at start and each scan) */
    private var shizukuAvailable = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var paused = false
    private var loopCount = 0
    private var energy = -1
    private var captureW = 0
    private var captureH = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    // Physical dimensions (always portrait: short=width, long=height), rotation-independent
    private var physicalShort = 0
    private var physicalLong = 0
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
            physicalShort = minOf(screenWidth, screenHeight)
            physicalLong = maxOf(screenWidth, screenHeight)
            captureW = screenWidth
            captureH = screenHeight
            log("Screen: ${screenWidth}x${screenHeight} density=$screenDensity physical=${physicalShort}x${physicalLong}")

            // Start MediaProjection
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)

            log("Capture size: ${captureW}x${captureH}")

            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)

            handlerThread = HandlerThread("ClickerThread").also { it.start() }
            handler = Handler(handlerThread!!.looper)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        log("MediaProjection stopped by system")
                        running = false
                        try { virtualDisplay?.release() } catch (_: Exception) {}
                        try { imageReader?.close() } catch (_: Exception) {}
                        virtualDisplay = null
                        imageReader = null
                    }
                }, handler)
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MobileClicker",
                captureW, captureH, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            running = true
            shizukuAvailable = checkShizukuStatus()
            log("Shizuku available: $shizukuAvailable")
            showBubble()

            // Mute the target game audio
            if (targetPackage.isNotEmpty() && shizukuAvailable) {
                muteTargetApp(true)
            }

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

    /**
     * Check if the current logical display size matches our capture dimensions.
     * If not (e.g., screen rotated), resize VirtualDisplay + rebuild ImageReader.
     */
    private fun ensureCaptureMatchesDisplay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val logicalW = metrics.widthPixels
        val logicalH = metrics.heightPixels

        if (logicalW == captureW && logicalH == captureH) return

        log("[CAPTURE] Display rotated: ${captureW}x${captureH} → ${logicalW}x${logicalH}, resizing")
        captureW = logicalW
        captureH = logicalH
        screenWidth = logicalW
        screenHeight = logicalH

        try {
            // Create new ImageReader with updated dimensions
            val newImageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            // Resize VirtualDisplay and swap surface (no need to recreate)
            virtualDisplay?.setSurface(newImageReader.surface)
            virtualDisplay?.resize(captureW, captureH, screenDensity)
            // Close old ImageReader after swapping
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = newImageReader
        } catch (e: Exception) {
            log("[CAPTURE] Resize failed: ${e.message}")
        }
    }

    private fun isTargetProcessAlive(): Boolean {
        if (targetPackage.isEmpty()) return true
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningAppProcesses()?.any { it.processName == targetPackage } == true
    }

    /** Check Shizuku availability (ping + permission) */
    private fun checkShizukuStatus(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun scanLoop() {
        if (!running) return
        if (paused) {
            handler?.postDelayed({ scanLoop() }, 1000)
            return
        }

        if (!isTargetProcessAlive()) {
            log("[SCAN] Target process '$targetPackage' is no longer running. Stopping.")
            running = false
            notify("Target game exited") {
                stopSelf()
            }
            return
        }


        try {
            ensureCaptureMatchesDisplay()

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

        // Query game window bounds via SurfaceFlinger (only if Shizuku available)
        if (targetPackage.isNotEmpty() && shizukuAvailable) {
            val detected = queryFromSurfaceFlinger(targetPackage)
            if (detected != null) {
                gameWindow = detected
                val gw = detected
                log("  [WINDOW] (${gw.left},${gw.top})-(${gw.right},${gw.bottom}) ${gw.width}x${gw.height} aspect=%.3f".format(gw.aspectRatio))
            } else {
                log("  [WINDOW] NOT DETECTED")
            }
        } else if (!shizukuAvailable) {
            // Assume fullscreen when Shizuku is not available
            gameWindow = GameWindow(0, 0, screenWidth, screenHeight)
        }

        // Debug: dump OCR items
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
        // Settlement: has "collect" button or challenge success
        if (items.any { "领取" in it.text || "挑战成功" in it.text }) return GameState.SETTLEMENT

        // Stage select
        if (items.any { "开始营业" in it.text || "经营目标" in it.text ||
                    "经营事件" in it.text || "辅助雇员" in it.text || "累计获得" in it.text }) {
            return GameState.STAGE_SELECT
        }

        // Gameplay: timer pattern
        val hasTimer = items.any { Regex("\\d+分\\d+秒").containsMatchIn(it.text) }
        if (hasTimer) return GameState.GAMEPLAY

        // Gameplay fallback: X/1,500 near top of game window
        val gw = gameWindow
        val topItems = if (gw != null) items.filter { gw.relY(it.cy) < 0.30 } else items
        if (parseRatio(topItems, 1500) != null) {
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
        parseRatio(items, 500)?.let { (cur, total) ->
            energy = cur
            log("  [ENERGY] $cur/$total")
            if (cur <= 0) {
                log("[STOP] Energy depleted!")
                running = false
                statusCallback?.invoke("Stopped: no energy")
                notify("Energy depleted after $loopCount loops") {
                    killGame()
                    stopSelf()
                }
                return
            }
        }

        val pos = items.find { "开始营业" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "start business")
        } else {
            val gw = gameWindow
            if (gw != null) {
                tap(gw.absX(START_BTN_REL_X), gw.absY(START_BTN_REL_Y), "start business (fallback)")
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
                    tap(gw.absX(EXIT_ICON_REL_X), gw.absY(EXIT_ICON_REL_Y), "exit icon", jitter = 5)
                } else {
                    log("  [ERROR] Cannot tap exit icon: game window not detected")
                }
                lastGameplayDelay = GAMEPLAY_FAST_MS
            } else {
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
        parseRatio(items, 500)?.let { (cur, total) ->
            energy = cur
            log("  [ENERGY] $cur/$total")
            if (cur <= 0) {
                log("[STOP] Energy depleted!")
                running = false
                statusCallback?.invoke("Stopped: no energy")
                notify("Energy depleted after $loopCount loops") {
                    killGame()
                    stopSelf()
                }
                return
            }
        }

        val pos = items.find { "领取" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "collect")
        } else {
            // Fallback: tap center of game window (for "挑战成功" screen where button is too small)
            val gw = gameWindow
            if (gw != null) {
                tap(gw.absX(0.5), gw.absY(0.75), "collect (fallback center)")
            }
        }

        loopCount++
        log("  [LOOP] Completed loop #$loopCount")
    }

    // ─── Ratio parsing ───────────────────────────────────────────────────────────

    private fun parseRatio(items: List<OcrItem>, expectedTotal: Int): Pair<Int, Int>? {
        val pattern = Regex("([\\d][\\d,. ]*\\d|\\d)\\s*/\\s*([\\d][\\d,. ]*\\d|\\d)")

        // Strategy 1: find ratio in single item
        for (item in items) {
            val result = matchRatioPattern(pattern, item.text, expectedTotal)
            if (result != null) return result
        }

        // Strategy 2: merge adjacent items on same row
        val sorted = items.sortedWith(compareBy({ it.cy / 30 }, { it.cx }))
        for (i in sorted.indices) {
            for (j in i + 1..minOf(i + 2, sorted.lastIndex)) {
                if (kotlin.math.abs(sorted[i].cy - sorted[j].cy) > 50) continue
                val merged = sorted[i].text + " " + sorted[j].text
                val result = matchRatioPattern(pattern, merged, expectedTotal)
                if (result != null) return result
            }
            // 3-item merge
            if (i + 2 <= sorted.lastIndex) {
                val items3 = sorted.subList(i, i + 3)
                if (items3.all { kotlin.math.abs(it.cy - items3[0].cy) <= 50 }) {
                    val merged = items3.joinToString(" ") { it.text }
                    val result = matchRatioPattern(pattern, merged, expectedTotal)
                    if (result != null) return result
                }
            }
        }

        // Strategy 3: find denominator standalone, look for numerator nearby
        val numPattern = Regex("([\\d][\\d,. ]*\\d|\\d)")
        for (item in items) {
            val allNums = numPattern.findAll(item.text).toList()
            for (m in allNums) {
                val num = m.value.replace(Regex("[,. ]"), "").toIntOrNull() ?: continue
                if (num in (expectedTotal * 9 / 10)..(expectedTotal * 11 / 10)) {
                    val nearby = items.filter {
                        it !== item &&
                        kotlin.math.abs(it.cy - item.cy) < 50 &&
                        it.cx < item.cx
                    }.sortedByDescending { it.cx }
                    for (nItem in nearby) {
                        val nMatch = numPattern.findAll(nItem.text).lastOrNull() ?: continue
                        val nVal = nMatch.value.replace(Regex("[,. ]"), "").toIntOrNull() ?: continue
                        if (nVal in 0..expectedTotal * 2) {
                            return nVal to num
                        }
                    }
                }
            }
        }

        return null
    }

    private fun matchRatioPattern(pattern: Regex, text: String, expectedTotal: Int): Pair<Int, Int>? {
        val match = pattern.find(text) ?: return null
        val cur = match.groupValues[1].replace(Regex("[,. ]"), "").toIntOrNull() ?: return null
        val total = match.groupValues[2].replace(Regex("[,. ]"), "").toIntOrNull() ?: return null
        if (total in (expectedTotal * 9 / 10)..(expectedTotal * 11 / 10)) {
            return cur to total
        }
        return null
    }

    // ─── Window detection via SurfaceFlinger ─────────────────────────────────────

    /**
     * Query game window bounds from SurfaceFlinger in PHYSICAL display coordinates.
     *
     * Parses Honor's `toDisplayTransform` format:
     *   geomBufferSize=[0 0 W H]
     *   toDisplayTransform={ scale x=SX y=SY tx=TX ty=TY }
     *
     * Physical bounds = (tx, ty) to (tx + W*SX, ty + H*SY)
     *
     * Falls back to standard AOSP `displayFrame=[l t r b]` if available.
     */
    private fun queryFromSurfaceFlinger(packageName: String): GameWindow? {
        if (!shizukuAvailable) return null
        try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "dumpsys SurfaceFlinger | grep -B 20 -A 5 '$packageName'"),
                null, null
            )
            val output = readProcessOutput(process, timeoutMs = 5000, maxBytes = 128 * 1024)
            if (output == null) {
                log("  [SF] dumpsys timeout")
                return null
            }

            // Run both strategies
            val displayFrameResult = parseDisplayFrame(output)
            val transformResult = parseToDisplayTransform(output, packageName)

            // If displayFrame gives a full-screen (or near full-screen) result, prefer it.
            // In full-screen mode, use logical (capture) dimensions as the game window,
            // since tap coordinates are in logical space after ensureCaptureMatchesDisplay().
            if (displayFrameResult != null) {
                val frameShort = minOf(displayFrameResult.width, displayFrameResult.height)
                val frameLong = maxOf(displayFrameResult.width, displayFrameResult.height)
                val isFullScreen = frameShort >= physicalShort - 20 && frameLong >= physicalLong - 20
                if (isFullScreen) {
                    return GameWindow(0, 0, captureW, captureH)
                }
                if (transformResult == null) {
                    return displayFrameResult
                }
            }

            // Otherwise use toDisplayTransform (small window mode on Honor)
            if (transformResult != null) return transformResult


            log("  [SF] No window info found for: $packageName")
            return null
        } catch (e: Exception) {
            log("  [SF] query failed: ${e.message}")
        }
        return null
    }

    /**
     * Parse Honor's toDisplayTransform format from SurfaceFlinger output.
     * Looks for geomBufferSize and toDisplayTransform in the same layer block.
     */
    private fun parseToDisplayTransform(output: String, packageName: String): GameWindow? {
        val bufferPattern = Regex("geomBufferSize=\\[\\s*\\d+\\s+\\d+\\s+(\\d+)\\s+(\\d+)\\s*]")
        val transformPattern = Regex("toDisplayTransform=\\{[^}]*?scale\\s+x=([\\d.]+)\\s+y=([\\d.]+)[^}]*?tx=([\\d.-]+)[^}]*?ty=([\\d.-]+)")

        val lines = output.lines()
        var bufW = 0
        var bufH = 0
        var foundBuffer = false

        for (line in lines) {
            // Look for geomBufferSize
            bufferPattern.find(line)?.let { m ->
                bufW = m.groupValues[1].toInt()
                bufH = m.groupValues[2].toInt()
                foundBuffer = true
            }

            // Look for toDisplayTransform
            transformPattern.find(line)?.let { m ->
                val scaleX = m.groupValues[1].toDoubleOrNull() ?: return@let
                val scaleY = m.groupValues[2].toDoubleOrNull() ?: return@let
                val tx = m.groupValues[3].toDoubleOrNull() ?: return@let
                val ty = m.groupValues[4].toDoubleOrNull() ?: return@let

                if (foundBuffer && bufW > 50 && bufH > 50 && scaleX > 0 && scaleY > 0) {
                    val left = tx.toInt()
                    val top = ty.toInt()
                    val right = (tx + bufW * scaleX).toInt()
                    val bottom = (ty + bufH * scaleY).toInt()
                    val w = right - left
                    val h = bottom - top

                    // Validate: result must fit within physical screen and be a reasonable window
                    if (w > 50 && h > 50 &&
                        left >= 0 && top >= 0 &&
                        right <= physicalLong + 5 && bottom <= physicalLong + 5) {
                        log("  [SF] toDisplayTransform: scale=(${scaleX},${scaleY}) tx=$tx ty=$ty buf=${bufW}x${bufH}")
                        log("  [SF] Physical bounds: ($left,$top)-($right,$bottom) ${w}x${h}")
                        return GameWindow(left, top, right, bottom)
                    }
                }
            }
        }
        return null
    }

    /**
     * Parse standard AOSP displayFrame=[l t r b] format.
     */
    private fun parseDisplayFrame(output: String): GameWindow? {
        val displayFramePattern = Regex("displayFrame=\\[(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)]")
        val frames = mutableListOf<GameWindow>()

        for (line in output.lines()) {
            val match = displayFramePattern.find(line) ?: continue
            val left = match.groupValues[1].toInt()
            val top = match.groupValues[2].toInt()
            val right = match.groupValues[3].toInt()
            val bottom = match.groupValues[4].toInt()
            val w = right - left
            val h = bottom - top
            if (w > 50 && h > 50) {
                frames.add(GameWindow(left, top, right, bottom))
            }
        }

        return frames.maxByOrNull { it.width.toLong() * it.height }
    }

    // ─── Shell & process utilities ───────────────────────────────────────────────

    /** Mute or unmute target app audio via appops. */
    private fun muteTargetApp(mute: Boolean) {
        val op = if (mute) "deny" else "allow"
        log("[AUDIO] ${if (mute) "Muting" else "Unmuting"} $targetPackage")
        shellExec("cmd appops set $targetPackage PLAY_AUDIO $op")
    }

    /** Execute a shell command via Shizuku (fire-and-forget). No-op if Shizuku unavailable. */
    private fun shellExec(cmd: String) {
        if (!shizukuAvailable) return
        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            Thread.sleep(500)
            try { process.destroyForcibly() } catch (_: Exception) {}
        } catch (e: Exception) {
            log("  [SHELL ERROR] $cmd: ${e.message}")
        }
    }

    /**
     * Read process output with a hard timeout.
     * Returns null if timeout is reached.
     */
    private fun readProcessOutput(process: Process, timeoutMs: Long, maxBytes: Int): String? {
        val result = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        result.appendLine(line)
                        if (result.length > maxBytes) break
                    }
                }
            } catch (_: Exception) {}
        }
        readerThread.start()
        readerThread.join(timeoutMs)
        if (readerThread.isAlive) {
            readerThread.interrupt()
            try { process.destroyForcibly() } catch (_: Exception) {}
            return null
        }
        try { process.destroyForcibly() } catch (_: Exception) {}
        return result.toString()
    }

    // ─── Tap & game control ──────────────────────────────────────────────────────

    private fun tap(x: Int, y: Int, label: String, jitter: Int = 15) {
        val rx = x + Random.nextInt(-jitter, jitter + 1)
        val ry = y + Random.nextInt(-jitter, jitter + 1)
        val gw = gameWindow
        val relInfo = if (gw != null && gw.width > 0 && gw.height > 0) {
            " rel=(%.3f, %.3f)".format(gw.relX(rx), gw.relY(ry))
        } else ""

        // Try AccessibilityService first, fall back to Shizuku input tap
        val a11y = ClickerAccessibilityService.instance
        if (a11y != null) {
            val dispatched = a11y.tap(rx.toFloat(), ry.toFloat())
            log("  [TAP·A11Y] ($rx, $ry)$relInfo $label ${if (dispatched) "✓" else "✗"}")
        } else if (shizukuAvailable) {
            log("  [TAP·SH] ($rx, $ry)$relInfo $label")
            shellExec("input tap $rx $ry")
        } else {
            log("  [TAP] SKIP ($rx, $ry) — no A11y and Shizuku unavailable")
        }
        Thread.sleep(Random.nextLong(200, 500))
    }

    private fun killGame() {
        if (targetPackage.isEmpty()) return
        val gw = gameWindow
        val frameShort = minOf(gw?.width ?: 0, gw?.height ?: 0)
        val frameLong = maxOf(gw?.width ?: 0, gw?.height ?: 0)
        val isFullscreen = gw != null &&
                frameShort >= physicalShort - 20 && frameLong >= physicalLong - 20
        if (isFullscreen) {
            val a11y = ClickerAccessibilityService.instance
            if (a11y != null) {
                log("[KILL] Fullscreen mode — A11y HOME")
                a11y.goHome()
            } else if (shizukuAvailable) {
                log("[KILL] Fullscreen mode — Shizuku HOME key")
                shellExec("input keyevent 3")
            } else {
                log("[KILL] Fullscreen mode — no method available to go HOME")
            }
        } else {
            if (shizukuAvailable) {
                log("[KILL] Freeform mode — force-stopping $targetPackage")
                shellExec("am force-stop $targetPackage")
            } else {
                log("[KILL] Freeform mode — Shizuku unavailable, cannot force-stop")
            }
        }
    }

    // ─── Image utilities ─────────────────────────────────────────────────────────

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

    private fun randomDelay(baseMs: Long): Long {
        val jitter = (baseMs * 0.3).toLong()
        return baseMs + Random.nextLong(-jitter, jitter + 1)
    }

    // ─── Notifications & logging ─────────────────────────────────────────────────

    private fun notify(msg: String, onComplete: (() -> Unit)? = null) {
        if (NTFY_TOPIC.isBlank()) {
            log("  [NTFY] Skipped (no topic configured)")
            onComplete?.invoke()
            return
        }
        Thread {
            try {
                val url = java.net.URL("https://ntfy.sh/$NTFY_TOPIC")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Title", "MobileClicker")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.outputStream.use { it.write(msg.toByteArray()) }
                conn.responseCode
                conn.disconnect()
                log("  [NTFY] Sent: $msg")
            } catch (e: Exception) {
                log("  [NTFY ERROR] ${e.message}")
            } finally {
                onComplete?.invoke()
            }
        }.start()
    }

    private fun log(msg: String) {
        android.util.Log.d("MobileClicker", msg)
        writeLogFile(msg)
    }

    private fun writeLogFile(msg: String) {
        try {
            val f = java.io.File(getExternalFilesDir(null), "mobileclicker.log")
            if (f.length() > LOG_MAX_BYTES) {
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

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onDestroy() {
        running = false
        // Restore target app audio
        if (targetPackage.isNotEmpty() && shizukuAvailable) {
            muteTargetApp(false)
        }
        removeBubble()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        handlerThread?.quitSafely()
        super.onDestroy()
    }

    // ─── Overlay bubble ──────────────────────────────────────────────────────────

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
        var longPressed = false
        val longPressTimeout = 800L
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                log("[BUBBLE] Long press → STOP")
                running = false
                statusCallback?.invoke("Stopped")
                killGame()
                stopSelf()
            }
        }

        tv.setOnTouchListener { _, event ->
            try {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downParamX = params.x
                        downParamY = params.y
                        moved = false
                        longPressed = false
                        tv.postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx * dx + dy * dy > 25) {
                            moved = true
                            tv.removeCallbacks(longPressRunnable)
                        }
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        wm.updateViewLayout(tv, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        tv.removeCallbacks(longPressRunnable)
                        if (!moved && !longPressed) {
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
}
