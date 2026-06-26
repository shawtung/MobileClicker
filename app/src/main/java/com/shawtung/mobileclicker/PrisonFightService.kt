package com.shawtung.mobileclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import rikka.shizuku.Shizuku
import kotlin.random.Random

class PrisonFightService : Service() {

    companion object {
        var statusCallback: ((String) -> Unit)? = null
        private const val CHANNEL_ID = "prisonfight_channel"

        // Target keyword for the scrolling activity list
        const val TARGET_ACTIVITY_NAME = "格斗争霸赛"
        // Also accept partial matches (OCR may drop characters)
        private val TARGET_KEYWORDS = listOf("格斗", "争霸赛", "格斗争霸")

        // Scan intervals (ms)
        const val SCAN_INTERVAL_MS = 2000L
        const val MATCHING_INTERVAL_MS = 5000L  // longer: nothing to do while waiting

        // Fixed button relative positions (estimated from screenshots, tune on device)
        // Main menu: 4th icon from right in bottom nav bar
        const val NAV_ICON_4_REL_X = 552.0 / 700
        const val NAV_ICON_4_REL_Y = 24.0 / 320
        // Activity list: "前往" button (bottom-right)
        const val GO_BTN_REL_X = 628.0 / 700
        const val GO_BTN_REL_Y = 296.0 / 320
        // Fight club: "随机匹配" button (right side)
        const val MATCH_BTN_REL_X = 552.0 / 700
        const val MATCH_BTN_REL_Y = 138.0 / 320

        // Activity list scroll region (relative)
        const val LIST_REL_X_LEFT = 46.0 / 700
        const val LIST_REL_X_RIGHT = 136.0 / 700
        const val LIST_REL_Y_TOP = 52.0 / 320
        const val LIST_REL_Y_BOTTOM = 248.0 / 320

        // Scroll search limits
        const val MAX_SCROLLS = 15
        const val SCROLL_DURATION_MS = 750L
        const val SCROLL_SETTLE_MS = 800L

        // Binarized OCR for art-text recognition
        const val LIST_COL_OCR_SCALE = 2f
        const val LIST_TEXT_LUMA = 150

        // ntfy.sh
        val NTFY_TOPIC = BuildConfig.NTFY_TOPIC
    }

    // ── State machine ──
    private enum class GameState { MAIN_MENU, ACTIVITY_LIST, FIGHT_CLUB, MATCHING, UNKNOWN }

    // ── Infrastructure (shared pattern with ClickerService) ──
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var paused = false
    private var loopCount = 0
    private var captureW = 0
    private var captureH = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var bubbleView: View? = null
    private var targetPackage: String = ""

    // Fullscreen game window (no SurfaceFlinger needed)
    private lateinit var gameWindow: GameWindow

    // State tracking
    private var nullImageCount = 0
    private var scrollCount = 0
    private var stuckCount = 0
    private var lastListSnapshot: String? = null
    private var activitySelected = false  // whether "格斗争霸赛" was tapped this visit

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // Reuse GameWindow data class from ClickerService (same package, accessible)
    // It's already a top-level class via ClickerService.GameWindow, but since it's inside
    // ClickerService's class body, we define our own identical one here for independence.
    data class GameWindow(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun absX(relX: Double) = left + (width * relX).toInt()
        fun absY(relY: Double) = top + (height * relY).toInt()
        fun relX(absX: Int) = (absX - left).toDouble() / width
        fun relY(absY: Int) = (absY - top).toDouble() / height
    }

    data class OcrItem(val text: String, val cx: Int, val cy: Int)

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
                .setContentTitle("PrisonFight")
                .setContentText("Running...")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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

            log("Service started: resultCode=$resultCode")
            targetPackage = intent?.getStringExtra("targetPackage") ?: ""

            if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                log("Error: No MediaProjection data")
                stopSelf()
                return START_NOT_STICKY
            }

            // Screen metrics — same convention as ClickerService: use the system's
            // current logical orientation directly. On this Honor device the display
            // rotates to ROTATION_90 when a landscape game is foregrounded, so
            // getRealMetrics() returns 2800x1280 (landscape) automatically. We must
            // NOT force max/min — that would double-rotate on devices that already
            // report landscape here.
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
            captureW = screenWidth
            captureH = screenHeight
            gameWindow = GameWindow(0, 0, screenWidth, screenHeight)
            log("Screen: ${screenWidth}x${screenHeight} density=$screenDensity")

            // MediaProjection
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)

            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)

            handlerThread = HandlerThread("PrisonFightThread").also { it.start() }
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
                "PrisonFight",
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

    /**
     * Re-read the display metrics every scan cycle so that gameWindow stays in sync
     * with the live orientation. The Service starts while the user is still in the
     * launcher/app (portrait), but the game runs in landscape. Without this resync
     * the window dimensions are frozen at the portrait size captured at startup and
     * every tap lands in the wrong place.
     *
     * When a rotation is detected the ImageReader + VirtualDisplay are also resized
     * so that MediaProjection captures match the new layout. (Same pattern as
     * ClickerService.ensureCaptureMatchesDisplay.)
     */
    private fun ensureCaptureMatchesDisplay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val logicalW = metrics.widthPixels
            val logicalH = metrics.heightPixels

            if (logicalW == captureW && logicalH == captureH) return
            if (logicalW < 100 || logicalH < 100) {
                log("[CAPTURE] Ignoring suspicious metrics: ${logicalW}x${logicalH}")
                return
            }

            log("[CAPTURE] Display rotated: ${captureW}x${captureH} → ${logicalW}x${logicalH}, resizing")
            captureW = logicalW
            captureH = logicalH
            screenWidth = logicalW
            screenHeight = logicalH
            gameWindow = GameWindow(0, 0, screenWidth, screenHeight)

            val newImageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.setSurface(newImageReader.surface)
            virtualDisplay?.resize(captureW, captureH, screenDensity)
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = newImageReader
        } catch (e: Exception) {
            log("[CAPTURE] Resize failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Scan loop ──

    private fun scanLoop() {
        if (!running) return
        if (paused) {
            handler?.postDelayed({ scanLoop() }, 1000)
            return
        }

        try {
            // Re-sync with the live display orientation every scan. The Service starts
            // while the user is still in the app (portrait), but the game runs in
            // landscape — without this, gameWindow stays frozen at the portrait size
            // captured at startup and every tap lands in the wrong place.
            ensureCaptureMatchesDisplay()

            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                nullImageCount++
                if (nullImageCount % 10 == 1) {
                    log("[SCAN] No image (count=$nullImageCount)")
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

            log("[SCAN] ${bitmap.width}x${bitmap.height}")
            maybeDumpFrame(bitmap)

            val inputImage = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        try {
                            handleOcrResult(visionText, bitmap)
                        } catch (t: Throwable) {
                            log("[ERROR] handleOcrResult crashed: ${t.javaClass.simpleName}: ${t.message}")
                            log(android.util.Log.getStackTraceString(t))
                            try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Exception) {}
                            scheduleNext(SCAN_INTERVAL_MS)
                        }
                    }
                    .addOnFailureListener { e ->
                        log("[ERROR] OCR failed: ${e.message}")
                        try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Exception) {}
                        scheduleNext(SCAN_INTERVAL_MS)
                    }
        } catch (e: Exception) {
            log("[SCAN] Error: ${e.message}")
            if (!running) return
            handler?.postDelayed({ scanLoop() }, SCAN_INTERVAL_MS)
        }
    }

    // ── OCR result handling ──

    private fun handleOcrResult(visionText: Text, bitmap: Bitmap) {
        val items = mutableListOf<OcrItem>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = (box.left + box.right) / 2
                val cy = (box.top + box.bottom) / 2
                items.add(OcrItem(line.text, cx, cy))
            }
        }

        log("  [OCR] ${items.size} items")
        for ((i, item) in items.withIndex()) {
            log("    [$i] (${item.cx},${item.cy}) rel=(%.3f,%.3f) \"${item.text}\""
                .format(gameWindow.relX(item.cx), gameWindow.relY(item.cy)))
        }

        val state = detectState(items)
        log("  [STATE] $state (loop #$loopCount)")
        statusCallback?.invoke("$state | Loop #$loopCount")

        when (state) {
            GameState.MAIN_MENU -> {
                handleMainMenu()
                finishScan(bitmap, SCAN_INTERVAL_MS)
            }
            GameState.ACTIVITY_LIST -> {
                // Run binarized OCR on the list column for better art-text recognition
                runListColumnOcr(bitmap, items) { enhanced ->
                    handleActivityList(enhanced)
                    finishScan(bitmap, SCAN_INTERVAL_MS)
                }
            }
            GameState.FIGHT_CLUB -> {
                handleFightClub(items)
                finishScan(bitmap, SCAN_INTERVAL_MS)
            }
            GameState.MATCHING -> {
                log("  [MATCHING] Waiting for battle to end...")
                finishScan(bitmap, MATCHING_INTERVAL_MS)
            }
            GameState.UNKNOWN -> {
                // Treat as MAIN_MENU — try tapping the nav icon; if we're mid-transition
                // it'll just do nothing and re-detect next cycle.
                log("  [UNKNOWN] → treating as MAIN_MENU")
                handleMainMenu()
                finishScan(bitmap, SCAN_INTERVAL_MS)
            }
        }
    }

    // ── State detection ──

    private fun detectState(items: List<OcrItem>): GameState {
        // MATCHING: highest priority. When "匹配成功" / "匹配中" / VS / countdown timer
        // appears, the fight is on (or loading into it) — the 随机匹配 button text may
        // still be visible but tapping it again is pointless and can glitch the UI.
        // MUST come before FIGHT_CLUB to avoid repeatedly re-tapping.
        if (items.any {
                val t = it.text
                "匹配中" in t || "匹配成功" in t || "VS" in t ||
                Regex("\\d+分\\d+秒").containsMatchIn(t)
            }) {
            return GameState.MATCHING
        }

        // FIGHT_CLUB: "随机匹配" button visible (waiting to be pressed)
        if (items.any { "随机匹配" in it.text || "随机" in it.text && "匹配" in it.text }) {
            return GameState.FIGHT_CLUB
        }

        // ACTIVITY_LIST: look for activity list markers — "前往" button visible
        val hasGoBtn = items.any { "前往" in it.text }
        // Also check for list-related UI: "活动" header
        val hasActivityHeader = items.any { "活动" in it.text }
        if (hasGoBtn || hasActivityHeader) {
            return GameState.ACTIVITY_LIST
        }

        // Default: MAIN_MENU (the bottom nav bar with game icons)
        return GameState.MAIN_MENU
    }

    // ── State handlers ──

    private fun handleMainMenu() {
        log("  [MAIN_MENU] Tapping 4th nav icon from right")
        tap(gameWindow.absX(NAV_ICON_4_REL_X), gameWindow.absY(NAV_ICON_4_REL_Y), "nav icon 4")
        // Reset activity list search state
        scrollCount = 0
        stuckCount = 0
        lastListSnapshot = null
        activitySelected = false
    }

    private fun handleActivityList(items: List<OcrItem>) {
        // If we already selected the activity, just tap "前往"
        if (activitySelected) {
            log("  [ACTIVITY_LIST] Activity selected → tapping 前往")
            tap(gameWindow.absX(GO_BTN_REL_X), gameWindow.absY(GO_BTN_REL_Y), "go button")
            activitySelected = false  // reset for next visit
            return
        }

        // Search for "格斗争霸赛" in the list
        val target = items.find { item ->
            TARGET_KEYWORDS.any { kw -> kw in item.text }
        }

        if (target != null) {
            log("  [ACTIVITY_LIST] Found target: \"${target.text}\" at (${target.cx},${target.cy})")
            tap(target.cx, target.cy, "select 格斗争霸赛")
            // Tap "前往" after a short delay
            Thread.sleep(300)
            tap(gameWindow.absX(GO_BTN_REL_X), gameWindow.absY(GO_BTN_REL_Y), "go button")
            activitySelected = false
            scrollCount = 0
            return
        }

        // Not found → scroll the list
        if (scrollCount >= MAX_SCROLLS) {
            log("[ACTIVITY_LIST] \"$TARGET_ACTIVITY_NAME\" not found after $MAX_SCROLLS scrolls. Aborting.")
            paused = true
            statusCallback?.invoke("Paused: activity not found")
            notify("\"$TARGET_ACTIVITY_NAME\" not found in activity list — paused")
            return
        }

        // Track if the list is actually moving
        val snapshot = listSnapshot(items)
        if (snapshot == lastListSnapshot) {
            stuckCount++
        } else {
            stuckCount = 0
            lastListSnapshot = snapshot
        }

        if (stuckCount >= 4) {
            log("[ACTIVITY_LIST] List stuck after $scrollCount scrolls — giving up.")
            paused = true
            statusCallback?.invoke("Paused: list stuck, can't find target")
            notify("Activity list stuck — paused")
            return
        }

        // Scroll down (to reveal more items below)
        scrollList()
        scrollCount++
    }

    private fun handleFightClub(items: List<OcrItem>) {
        log("  [FIGHT_CLUB] Tapping 随机匹配")
        // Try OCR position first, fall back to fixed rel
        val pos = items.find { "随机匹配" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "random match (OCR)")
        } else {
            tap(gameWindow.absX(MATCH_BTN_REL_X), gameWindow.absY(MATCH_BTN_REL_Y), "random match (fallback)")
        }
    }

    // ── Scroll the activity list ──

    private fun scrollList() {
        val x = gameWindow.absX((LIST_REL_X_LEFT + LIST_REL_X_RIGHT) / 2).toFloat()
        val yHi = gameWindow.absY(LIST_REL_Y_TOP + 0.1).toFloat()
        val yLo = gameWindow.absY(LIST_REL_Y_BOTTOM - 0.1).toFloat()

        log("  [SCROLL] #$scrollCount x=${x.toInt()} y=${yHi.toInt()}..${yLo.toInt()}")

        val a11y = ClickerAccessibilityService.instance
        if (a11y != null) {
            // Swipe down (drag from top to bottom) to scroll list down
            a11y.swipe(x, yHi, x, yLo, SCROLL_DURATION_MS)
        } else {
            log("  [SCROLL] No A11y — cannot scroll")
            return
        }
        Thread.sleep(SCROLL_SETTLE_MS)
    }

    private fun listSnapshot(items: List<OcrItem>): String {
        val band = gameWindow.width * 0.10
        val listItems = items.filter {
            gameWindow.relX(it.cx) in LIST_REL_X_LEFT..LIST_REL_X_RIGHT &&
            gameWindow.relY(it.cy) in LIST_REL_Y_TOP..LIST_REL_Y_BOTTOM
        }
        return listItems.sortedBy { it.cy * 10000 + it.cx }
            .joinToString("|") {
                val t = it.text.filter { c -> !c.isDigit() && c != ':' }.trim()
                "${it.cy / 20}:$t"
            }
    }

    // ── Binarized OCR for art-text (white-fill + dark outline) ──

    private fun runListColumnOcr(
        src: Bitmap,
        generalItems: List<OcrItem>,
        onDone: (List<OcrItem>) -> Unit
    ) {
        val cropLeft = (src.width * LIST_REL_X_LEFT).toInt().coerceAtLeast(0)
        val cropRight = (src.width * LIST_REL_X_RIGHT).toInt().coerceAtMost(src.width)
        val cropTop = (src.height * LIST_REL_Y_TOP).toInt().coerceAtLeast(0)
        val cropBottom = (src.height * LIST_REL_Y_BOTTOM).toInt().coerceAtMost(src.height)
        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        if (cropW < 20 || cropH < 20) {
            onDone(generalItems)
            return
        }

        val processed: Bitmap
        val scale: Float
        try {
            val crop = Bitmap.createBitmap(src, cropLeft, cropTop, cropW, cropH)
            scale = LIST_COL_OCR_SCALE
            val up = Bitmap.createScaledBitmap(
                crop, (cropW * scale).toInt(), (cropH * scale).toInt(), true
            )
            if (up !== crop) crop.recycle()

            val w = up.width
            val h = up.height
            val px = IntArray(w * h)
            up.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (r * 30 + g * 59 + b * 11) / 100
                // Bright fill (art-text body) → black; dark outline & background → white
                px[i] = if (luma > LIST_TEXT_LUMA) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            up.setPixels(px, 0, w, 0, 0, w, h)
            processed = up
        } catch (e: Exception) {
            log("  [BIN-OCR] preprocess failed: ${e.message}")
            onDone(generalItems)
            return
        }

        recognizer.process(InputImage.fromBitmap(processed, 0))
            .addOnSuccessListener { vt ->
                try {
                    val colItems = mutableListOf<OcrItem>()
                    for (block in vt.textBlocks) for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        // Map coordinates back to original image space
                        val cx = (box.left + box.right) / 2
                        val cy = (box.top + box.bottom) / 2
                        val mappedCx = (cx / scale + cropLeft).toInt()
                        val mappedCy = (cy / scale + cropTop).toInt()
                        colItems.add(OcrItem(line.text, mappedCx, mappedCy))
                    }
                    processed.recycle()
                    val merged = generalItems + colItems
                    log("  [BIN-OCR] general ${generalItems.size} + binarized ${colItems.size}")
                    onDone(merged)
                } catch (t: Throwable) {
                    log("[BIN-OCR] callback crashed: ${t.javaClass.simpleName}: ${t.message}")
                    try { processed.recycle() } catch (_: Exception) {}
                    onDone(generalItems)
                }
            }
            .addOnFailureListener { e ->
                processed.recycle()
                log("  [BIN-OCR] failed: ${e.message}")
                onDone(generalItems)
            }
    }

    // ── Tap (shared pattern) ──

    private fun tap(x: Int, y: Int, label: String, jitter: Int = 15) {
        val rx = x + Random.nextInt(-jitter, jitter + 1)
        val ry = y + Random.nextInt(-jitter, jitter + 1)
        val relInfo = " rel=(%.3f, %.3f)".format(gameWindow.relX(rx), gameWindow.relY(ry))

        val a11y = ClickerAccessibilityService.instance
        if (a11y != null) {
            val dispatched = a11y.tap(rx.toFloat(), ry.toFloat())
            log("  [TAP·A11Y] ($rx, $ry)$relInfo $label ${if (dispatched) "✓" else "✗"}")
        } else {
            log("  [TAP] SKIP ($rx, $ry) — no A11y service")
        }
        Thread.sleep(Random.nextLong(200, 500))
    }

    // ── Utilities ──

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

    private fun finishScan(bitmap: Bitmap, delay: Long) {
        if (!bitmap.isRecycled) bitmap.recycle()
        if (running) scheduleNext(delay)
    }

    private fun scheduleNext(delayMs: Long) {
        val jitter = (delayMs * 0.3).toLong()
        val delay = delayMs + Random.nextLong(-jitter, jitter + 1)
        handler?.postDelayed({ scanLoop() }, delay)
    }

    private fun maybeDumpFrame(bitmap: Bitmap) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val flag = java.io.File(dir, "dump.flag")
            if (!flag.exists()) return
            val out = java.io.File(dir, "frame_raw.png")
            java.io.FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            flag.delete()
            log("[DUMP] saved frame ${bitmap.width}x${bitmap.height} -> ${out.name}")
        } catch (e: Exception) {
            log("[DUMP] failed: ${e.message}")
        }
    }

    // ── Notifications & logging ──

    private fun notify(msg: String, onComplete: (() -> Unit)? = null) {
        if (NTFY_TOPIC.isBlank()) {
            log("  [NTFY] Skipped (no topic)")
            onComplete?.invoke()
            return
        }
        Thread {
            try {
                val url = java.net.URL("https://ntfy.sh/$NTFY_TOPIC")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Title", "PrisonFight")
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
        android.util.Log.d("PrisonFight", msg)
        try {
            val f = java.io.File(getExternalFilesDir(null), "prisonfight.log")
            if (f.length() > 500 * 1024L) {
                f.writeText(f.readText().takeLast(200 * 1024))
            }
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n")
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PrisonFight", NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    // ── Lifecycle ──

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
        val result = BubbleHelper.createBubble(
            context = this,
            onTogglePause = { isPaused ->
                paused = isPaused
                log(if (paused) "[PAUSED]" else "[RESUMED]")
                statusCallback?.invoke(if (paused) "Paused" else "Running")
            },
            onLongPressStop = {
                log("[BUBBLE] Long press → STOP")
                running = false
                statusCallback?.invoke("Stopped")
                stopSelf()
            }
        )
        if (result == null) {
            log("[BUBBLE] No overlay permission")
            return
        }
        val (view, params) = result
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, params)
        bubbleView = view
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
}
