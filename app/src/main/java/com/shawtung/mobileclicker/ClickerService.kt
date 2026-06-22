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
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
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
        // Max city-vitality (都市活力) shown as "X/700"; used to read remaining energy.
        const val ENERGY_TOTAL = 700
        const val SCAN_INTERVAL_MS = 3000L
        // Settlement screens (挑战成功 → 结算 → 领取) need tapping-through; scan faster.
        const val SETTLEMENT_INTERVAL_MS = 1000L
        const val GAMEPLAY_SLOW_MS = 8000L
        const val GAMEPLAY_FAST_MS = 2000L
        const val LOG_MAX_BYTES = 500 * 1024L

        // Power saving: these per-scan Shizuku calls (SurfaceFlinger dump, pidof) are
        // expensive (fork + heavy dumpsys). The game window doesn't move while AFK and
        // the game process doesn't die mid-loop, so we cache/throttle both heavily
        // instead of paying the cost every 1–8s scan.
        const val WINDOW_REFRESH_MS = 30_000L
        const val PROC_CHECK_INTERVAL_MS = 30_000L

        // Exit icon relative position within game content area
        // Empirically calibrated: successful hits at rel ~(0.054-0.059, 0.039-0.041)
        const val EXIT_ICON_REL_X = 0.057
        const val EXIT_ICON_REL_Y = 0.039
        // "领取" (collect) button on the settlement screen. Used as a fallback tap when OCR
        // can't read the small button text (esp. small-window mode). From observed rel pos.
        const val COLLECT_REL_X = 0.596
        const val COLLECT_REL_Y = 0.804

        // Start button fallback relative position
        const val START_BTN_REL_X = 0.845  // 2365/2800
        const val START_BTN_REL_Y = 0.938  // 1200/1280

        // ── Stage selection: only chapter-level 1-9 supports AFK farming ──
        const val TARGET_CHAPTER = 1
        const val TARGET_LEVEL = 9
        // Distinctive name keyword(s) of stage 1-9 ("团三郎大突袭"). Used to confirm
        // selection when OCR drops the leading "1" of the "1-9" header (→ "-9"). The
        // name is unique to this level, so it identifies 1-9 even when the id is garbled.
        val TARGET_NAME_HINTS = listOf("三郎", "突袭")
        // Safety caps for the scroll-and-find loop
        const val STAGE_MAX_SCROLLS = 20
        const val STAGE_MAX_SELECT_TAPS = 4
        // Only trust row-offset positioning when the anchor is this close (in levels)
        // to the target — keeps the chance of an OCR-dropped intermediate row low.
        const val STAGE_OFFSET_MAX_DELTA = 4
        // Stage numbers are LIGHT-fill bubble glyphs (light interior + dark outline) on a
        // mid-gray panel. Keeping the light FILL as ink yields solid, readable digits
        // (confirmed best vs dark-outline-only / band-stop). Pixels BRIGHTER than this
        // threshold become black text, everything else white.
        const val STAGE_COL_FRAC = 0.28
        const val STAGE_OCR_SCALE = 2f
        const val STAGE_TEXT_LUMA = 150

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
    /** elapsedRealtime() of the last SurfaceFlinger window query (for caching) */
    private var lastWindowQueryMs = 0L
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
        // Coordinates changed — invalidate the cached game window so the next scan
        // re-queries SurfaceFlinger immediately instead of using stale bounds.
        gameWindow = null
        lastWindowQueryMs = 0L

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

        // Throttle: forking a Shizuku `pidof` process every scan is expensive and the
        // game won't die mid-loop. Only re-check periodically; assume alive in between.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProcCheckMs < PROC_CHECK_INTERVAL_MS) return lastProcAlive
        lastProcCheckMs = now

        // Preferred: ask the system via Shizuku. `getRunningAppProcesses()` only
        // returns our OWN process on Android 7+, so it can never see the game and
        // would always report it as dead.
        if (shizukuAvailable) {
            try {
                val process = Shizuku.newProcess(
                    arrayOf("sh", "-c", "pidof $targetPackage"),
                    null, null
                )
                val output = readProcessOutput(process, timeoutMs = 3000, maxBytes = 4 * 1024)
                if (output != null) {
                    // pidof prints space-separated PIDs and is empty when not running.
                    lastProcAlive = output.trim().isNotEmpty()
                    return lastProcAlive
                }
                // On timeout/failure, fall through and don't kill the service.
            } catch (e: Exception) {
                log("  [PROC] pidof check failed: ${e.message}")
            }
            // If the Shizuku check was inconclusive, assume alive to avoid false stops.
            lastProcAlive = true
            return true
        }

        // Fallback (no Shizuku): best-effort via ActivityManager. This usually only
        // sees our own process, so treat a positive match as alive but never use a
        // negative result to stop the service.
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val seen = am.getRunningAppProcesses()?.any { it.processName == targetPackage } == true
        if (seen) return true
        // Can't reliably tell — assume still alive.
        lastProcAlive = true
        return true
    }

    // Throttling state for isTargetProcessAlive().
    private var lastProcCheckMs = 0L
    private var lastProcAlive = true

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
            maybeDumpFrame(bitmap)

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    // bitmap is now owned by handleOcrResult (recycled in finishScan,
                    // possibly after a second async stage-column OCR pass).
                    handleOcrResult(visionText, w, h, bitmap)
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

    private fun handleOcrResult(visionText: Text, imgW: Int, imgH: Int, bitmap: Bitmap) {
        val items = mutableListOf<OcrItem>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = (box.left + box.right) / 2
                val cy = (box.top + box.bottom) / 2
                items.add(OcrItem(line.text, cx, cy))
            }
        }

        // Query game window bounds via SurfaceFlinger (only if Shizuku available).
        // This `dumpsys SurfaceFlinger` is heavy and is ONLY needed in small-window
        // mode (to recover the physical tx/ty/scale). In fullscreen the window is just
        // the whole capture area, which we already know — so once we detect fullscreen
        // we stop querying entirely. Small-window is re-synced every WINDOW_REFRESH_MS
        // (in case the user moves/resizes it). Rotation nulls gameWindow → re-query.
        if (targetPackage.isNotEmpty() && shizukuAvailable) {
            val gw = gameWindow
            val isFullscreenWin = gw != null &&
                    gw.left == 0 && gw.top == 0 && gw.right == captureW && gw.bottom == captureH
            val now = android.os.SystemClock.elapsedRealtime()
            val stale = gw == null ||
                    (!isFullscreenWin && now - lastWindowQueryMs >= WINDOW_REFRESH_MS)
            if (stale) {
                val detected = queryFromSurfaceFlinger(targetPackage)
                if (detected != null) {
                    gameWindow = detected
                    lastWindowQueryMs = now
                    log("  [WINDOW] (${detected.left},${detected.top})-(${detected.right},${detected.bottom}) ${detected.width}x${detected.height} aspect=%.3f".format(detected.aspectRatio))
                } else {
                    log("  [WINDOW] NOT DETECTED")
                }
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

        if (state != GameState.STAGE_SELECT) resetStageSearch()

        when (state) {
            GameState.MAP -> {
                handleMap(items)
                finishScan(bitmap, SCAN_INTERVAL_MS)
            }
            GameState.STAGE_SELECT -> {
                // Stage ids use a decorative font OCR reads poorly (e.g. the "-" in
                // "1-9" gets eaten). Run a dedicated, preprocessed (cropped + upscaled +
                // binarized) OCR of the left column for far cleaner ids, then run the
                // stage logic with those merged in.
                runStageColumnOcr(bitmap, items) { enhanced ->
                    handleStageSelect(enhanced)
                    finishScan(bitmap, SCAN_INTERVAL_MS)
                }
            }
            GameState.GAMEPLAY -> {
                handleGameplay(items)
                finishScan(bitmap, lastGameplayDelay)
            }
            GameState.SETTLEMENT -> {
                dumpFrameIfFlag(bitmap, "settle.flag", "frame_settle.png")
                handleSettlement(items)
                finishScan(bitmap, SETTLEMENT_INTERVAL_MS)
            }
            GameState.UNKNOWN -> {
                val preview = items.take(5).joinToString(", ") { it.text }
                log("  [UNKNOWN] $preview")
                finishScan(bitmap, SCAN_INTERVAL_MS)
            }
        }
    }

    /** Recycle the scan bitmap and schedule the next scan. */
    private fun finishScan(bitmap: Bitmap, delay: Long) {
        if (!bitmap.isRecycled) bitmap.recycle()
        if (running) scheduleNext(delay)
    }

    private fun detectState(items: List<OcrItem>): GameState {
        // Settlement vs stage-select disambiguation.
        //
        // The real settlement popup DIMS the stage-select panel behind it (measured:
        // 开始营业/经营目标/辅助雇员 regions drop to luma ~30-80 and stop being OCR'd),
        // while its "领取" button stays bright. So we can safely give stage-select
        // markers priority over the *loose* settlement glyphs.
        //
        // The loose "取"/"成功"/"失败" single-glyph match is needed for garbled "领取",
        // but it also matches innocent short UI words on the stage-select screen
        // (e.g. top-bar "待取货", or "获取奖励" in the event blurb) — which previously
        // hijacked stage-select into a fake settlement loop forever. Ordering fixes it:

        // 1. Specific settlement button — unambiguous, always wins.
        if (items.any { "领取" in it.text || "領取" in it.text }) {
            return GameState.SETTLEMENT
        }

        // 2. Stage select. On a real settlement screen these are dimmed/unreadable,
        //    so reaching here means we're genuinely on the stage-select screen.
        if (items.any { "开始营业" in it.text || "经营目标" in it.text ||
                    "经营事件" in it.text || "辅助雇员" in it.text || "累计获得" in it.text }) {
            return GameState.STAGE_SELECT
        }

        // 3. Map. The "店长特供" marker is OCR-mangled ("店"→"日", "特"→"持", stray "("),
        //    so match the more stable "特供"/"持供" tail. Checked BEFORE the loose
        //    settlement fallback so stray glyphs on the map can't flag a fake settlement.
        if (items.any { isStoreSpecial(it.text) }) return GameState.MAP

        // 4. Loose settlement fallback (garbled 领取 / 挑战成功·失败 banner). Only fires
        //    when no stage-select / map markers are present, so top-bar "待取货"/"获取"
        //    can no longer trigger it.
        if (items.any {
                val t = it.text
                t.length <= 5 && ("取" in t || "成功" in t || "失败" in t)
            }) {
            return GameState.SETTLEMENT
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

        return GameState.UNKNOWN
    }

    /** Robust "店长特供" map marker match, tolerant of OCR garbling (店→日, 特→持). */
    private fun isStoreSpecial(t: String): Boolean =
        "店长特供" in t || "特供" in t || "持供" in t || ("长" in t && "供" in t)

    private fun handleMap(items: List<OcrItem>) {
        val pos = items.find { isStoreSpecial(it.text) }
        if (pos != null) {
            tap(pos.cx, pos.cy, "store special")
        }
    }

    private fun handleStageSelect(items: List<OcrItem>) {
        parseRatio(items, ENERGY_TOTAL)?.let { (cur, total) ->
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

        // Make sure the target stage (1-9, the only AFK-able one) is selected,
        // scrolling the left list + OCR as needed, then press "开始营业".
        ensureTargetStageAndStart(items)
    }

    /** Press the "开始营业" button (by OCR position, falling back to fixed rel pos). */
    private fun pressStartBusiness(items: List<OcrItem>) {
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

    private fun ensureTargetStageAndStart(items: List<OcrItem>) {
        if (stageSearchAborted) {
            // Already gave up this session; do nothing (service is paused/notified).
            return
        }

        // Detect the stage list straight from OCR geometry (NOT from gameWindow,
        // which is unreliable in small-window mode and can mis-scale rel coords).
        val list = detectStageList(items)
        val rows = list?.rows ?: emptyList()
        val parsed = rows.mapIndexedNotNull { i, r -> r.id?.let { i to it } }

        if (list != null) {
            val selTarget = selectedIsTarget(items, list)
            val targetRowIndex = locateTargetRowIndex(rows, parsed)
            log("  [STAGE] target=$TARGET_CHAPTER-$TARGET_LEVEL selectedIsTarget=$selTarget colX=${list.colX} rows=[" +
                    rows.joinToString(",") { it.id?.let { id -> "${id.first}-${id.second}" } ?: "?" } + "]")

            // 1) Start only if confirmed AND either the target row isn't visible (so a
            // merged list-row can't be faking the confirm) OR we've already actively
            // tapped it. OCR sometimes merges the 1-9 LIST row into the detail header
            // while a DIFFERENT stage (e.g. 1-10) is actually selected — which would
            // falsely confirm. So when 1-9 is visible, force-select it first (step 2).
            if (selTarget && (targetRowIndex == null || stageTargetTapCount > 0)) {
                log("  [STAGE] Target selected ✓ → start")
                resetStageSearch()
                pressStartBusiness(items)
                return
            }

            // 2) Locate the target row — directly, or by offset from a same-chapter anchor.
            if (targetRowIndex != null) {
                val row = rows[targetRowIndex]
                tap(row.cx, row.cy, "select stage $TARGET_CHAPTER-$TARGET_LEVEL")
                stageTargetTapCount++
                // Never blind-start: only press "开始营业" once step 1 confirms 1-9
                // selected (after this tap). Otherwise abort + notify after a few taps.
                if (stageTargetTapCount >= STAGE_MAX_SELECT_TAPS) {
                    log("[STAGE] Tapped target ${stageTargetTapCount}x but selection unconfirmed. Aborting.")
                    stageSearchAborted = true
                    paused = true
                    statusCallback?.invoke("Paused: can't confirm $TARGET_CHAPTER-$TARGET_LEVEL selected")
                    notify("Stage $TARGET_CHAPTER-$TARGET_LEVEL selection unconfirmed — paused")
                }
                return
            }
        } else {
            log("  [STAGE] No stage list detected this frame")
        }

        // 3) Not locatable in this frame → scroll toward the target.
        // Decide direction: prefer OCR ids; otherwise reuse the last known direction
        // (so OCR-blind frames keep going the right way instead of always scrolling up).
        if (parsed.isNotEmpty()) {
            val target = TARGET_CHAPTER * 100 + TARGET_LEVEL
            val ords = parsed.map { it.second.first * 100 + it.second.second }
            stageRevealEarlier = when {
                target < ords.min() -> true   // target is above everything visible
                target > ords.max() -> false  // target is below everything visible
                else -> true                  // within range but unread row — nudge up
            }
            stageDirReversed = false          // ids give reliable direction; clear edge-flip
        } else if (stageRevealEarlier == null) {
            // Nothing established yet: the game usually lands on the LAST stage, so the
            // target is typically above → scroll up first. Edge-detection will flip us
            // if we're actually already at the top.
            stageRevealEarlier = true
        }

        // Track progress with a position-stable snapshot; abort/flip if stuck.
        if (maybeAbortScroll(stageSnapshot(items, list))) return

        doStageScroll(revealEarlier = stageRevealEarlier ?: true, list = list)
    }

    /** Track scroll progress; reverse at an edge, then abort (pause + notify) if stuck/over cap. */
    private fun maybeAbortScroll(snapshot: String): Boolean {
        if (snapshot == lastStageSnapshot) {
            stageScrollStuckCount++
        } else {
            stageScrollStuckCount = 0
            lastStageSnapshot = snapshot
        }
        // List isn't moving → we hit the top/bottom. Flip direction once and keep going
        // instead of giving up (handles "started at the top, target is below").
        if (stageScrollStuckCount >= 2 && !stageDirReversed && stageRevealEarlier != null) {
            stageRevealEarlier = !(stageRevealEarlier!!)
            stageDirReversed = true
            stageScrollStuckCount = 0
            lastStageSnapshot = null
            log("  [STAGE] Scroll stuck at edge → reversing to " +
                    if (stageRevealEarlier == true) "earlier↑" else "later↓")
            return false
        }
        if (stageScrollCount >= STAGE_MAX_SCROLLS || stageScrollStuckCount >= 4) {
            log("[STAGE] Could not locate $TARGET_CHAPTER-$TARGET_LEVEL after " +
                    "$stageScrollCount scrolls (stuck=$stageScrollStuckCount). Aborting.")
            stageSearchAborted = true
            paused = true
            statusCallback?.invoke("Paused: stage $TARGET_CHAPTER-$TARGET_LEVEL not found")
            notify("Stage $TARGET_CHAPTER-$TARGET_LEVEL not found — paused")
            return true
        }
        return false
    }

    /**
     * Find the row index of the target stage.
     *  - Direct: a row whose OCR parsed exactly to the target id.
     *  - Offset: pick the same-chapter anchor closest to the target level, then step
     *    by the level delta (rows are evenly spaced & consecutive within a chapter).
     */
    private fun locateTargetRowIndex(
        rows: List<StageRow>,
        parsed: List<Pair<Int, Pair<Int, Int>>>
    ): Int? {
        // Direct match.
        parsed.find { it.second.first == TARGET_CHAPTER && it.second.second == TARGET_LEVEL }
            ?.let { return it.first }

        // Offset from the nearest same-chapter anchor.
        val anchor = parsed
            .filter { it.second.first == TARGET_CHAPTER }
            .minByOrNull { kotlin.math.abs(it.second.second - TARGET_LEVEL) }
            ?: return null
        val anchorIdx = anchor.first
        val anchorLevel = anchor.second.second
        // Don't extrapolate too far — a single OCR-dropped row in between would
        // shift the index and select the wrong stage. Scroll closer first instead.
        if (kotlin.math.abs(anchorLevel - TARGET_LEVEL) > STAGE_OFFSET_MAX_DELTA) return null
        val idx = anchorIdx + (TARGET_LEVEL - anchorLevel)
        if (idx !in rows.indices) return null
        // Sanity: if another same-chapter anchor exists, the spacing must be consistent.
        val inconsistent = parsed.any { (i, id) ->
            id.first == TARGET_CHAPTER && i != anchorIdx &&
                    (i - anchorIdx) != (id.second - anchorLevel)
        }
        if (inconsistent) return null
        return idx
    }

    /** A visual row in the left stage list: tap point + (optional) parsed id. */
    private data class StageRow(val cx: Int, val cy: Int, val id: Pair<Int, Int>?)

    /** Detected stage list: rows + column x + vertical span + row spacing. */
    private data class StageList(
        val rows: List<StageRow>,
        val colX: Int,
        val topCy: Int,
        val botCy: Int,
        val rowSpacing: Int
    )

    /** Normalize common OCR digit confusions before parsing stage ids. */
    private fun normalizeStageDigits(s: String): String =
        s.replace('O', '0').replace('o', '0')
            .replace('l', '1').replace('I', '1').replace('|', '1')

    /** Parse an "N-M" stage id from a text fragment (requires the dash). */
    private fun parseStageId(text: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})\\s*[-\u2013\u2014\uFF0D]\\s*(\\d{1,2})")
            .find(normalizeStageDigits(text)) ?: return null
        val ch = m.groupValues[1].toIntOrNull() ?: return null
        val lv = m.groupValues[2].toIntOrNull() ?: return null
        // Reject implausible ids: real chapters/levels are small, so a parsed level like
        // "84" (OCR doubling a bubble digit, e.g. "2-8"→"2-84") is garbage, not a stage.
        if (ch !in 1..20 || lv !in 1..20) return null
        return ch to lv
    }

    /**
     * Detect the left-side stage list from OCR geometry alone (window-independent).
     *
     * 1. Collect items whose text parses a clean "N-M" id.
     * 2. The list is the *leftmost dense vertical cluster* of those (the right-side
     *    detail header like "3-10钢琴家" sits further right and is a singleton).
     * 3. Re-cluster ALL items near that column into rows (so OCR-garbled rows like
     *    "19 EABo" still occupy their slot for offset positioning).
     */
    private fun detectStageList(items: List<OcrItem>): StageList? {
        val band = (minOf(captureW, captureH) * 0.06).toInt().coerceAtLeast(40)

        val cands = items.filter { parseStageId(it.text) != null }
        if (cands.isEmpty()) return null

        // Leftmost, densest cluster by cx.
        val sortedByX = cands.sortedBy { it.cx }
        var best: List<OcrItem> = emptyList()
        for (c in sortedByX) {
            val group = cands.filter { it.cx in c.cx..(c.cx + band) }
            // Prefer more members; on ties keep the leftmost (earlier in iteration).
            if (group.size > best.size) best = group
        }
        if (best.isEmpty()) return null  // need at least one anchor
        val colX = best.map { it.cx }.sorted().let { it[it.size / 2] }

        // All items sharing this column (any cy), top→bottom.
        val colAll = items.filter { kotlin.math.abs(it.cx - colX) <= band }.sortedBy { it.cy }
        if (colAll.size < 2) return null

        // Median consecutive gap is the true row spacing — robust even though the
        // app's own clock/status text up top adds one huge outlier gap.
        val gapsAll = colAll.zipWithNext { a, b -> b.cy - a.cy }.filter { it > 0 }.sorted()
        val medGap = if (gapsAll.isEmpty()) (minOf(captureW, captureH) * 0.05).toInt()
                     else gapsAll[gapsAll.size / 2]
        val breakGap = (medGap * 1.8).toInt().coerceAtLeast(60)

        // Split into contiguous runs; the stage list is the largest run, which
        // naturally drops far-away outliers (e.g. the top clock) into tiny runs.
        val runs = mutableListOf<MutableList<OcrItem>>()
        var run = mutableListOf(colAll.first())
        for (i in 1 until colAll.size) {
            if (colAll[i].cy - run.last().cy > breakGap) {
                runs.add(run); run = mutableListOf(colAll[i])
            } else run.add(colAll[i])
        }
        runs.add(run)
        val listRun = runs.maxByOrNull { it.size } ?: return null
        if (listRun.size < 2) return null
        // In chapter 1, OCR garbles most ids ("H"/"19"/"H0"…) so often only ONE row
        // parses. Accept a single anchor only when the column is clearly a real list
        // (enough rows) to avoid latching onto a stray N-M match elsewhere on screen.
        val parsedInRun = listRun.count { parseStageId(it.text) != null }
        if (parsedInRun == 0) return null
        if (parsedInRun < 2 && listRun.size < 4) return null

        // Cluster the run into visual rows. Threshold ≈ half a row so adjacent noise
        // (e.g. a star-rating glyph) merges into its row without bridging two rows.
        val rowGapPx = (medGap * 0.5).toInt().coerceAtLeast(15)
        val rows = mutableListOf<StageRow>()
        var cluster = mutableListOf(listRun.first())
        fun flush() {
            // Prefer the id-bearing item as the row's tap point so a merged neighbour
            // can never shift the tap off the actual stage text.
            val idItem = cluster.firstOrNull { parseStageId(it.text) != null }
            val rep = idItem ?: cluster.minByOrNull { it.cx } ?: cluster.first()
            val id = idItem?.let { parseStageId(it.text) }
            rows.add(StageRow(rep.cx, rep.cy, id))
        }
        for (i in 1 until listRun.size) {
            if (listRun[i].cy - cluster.last().cy > rowGapPx) {
                flush(); cluster = mutableListOf(listRun[i])
            } else cluster.add(listRun[i])
        }
        flush()
        if (rows.isEmpty()) return null

        val rowSpacing = if (rows.size > 1) (rows.last().cy - rows.first().cy) / (rows.size - 1)
                         else medGap
        return StageList(rows, colX, rows.first().cy, rows.last().cy, rowSpacing.coerceAtLeast(20))
    }

    /**
     * Whether the selected-stage header (detail panel, right of the list) echoes the
     * target. Crucially, OCR can MERGE a left-list row with the detail header into one
     * line (e.g. "1-9 …大突袭 @3-10 钢琴家" when 3-10 is the selected special-event
     * stage). So we never confirm on a bare "contains 1-9": we require that EVERY stage
     * id seen in the detail region is the target — any other id means a different stage
     * is actually selected.
     */
    private fun selectedIsTarget(items: List<OcrItem>, list: StageList): Boolean {
        val band = (minOf(captureW, captureH) * 0.06).toInt().coerceAtLeast(40)
        val cxMin = list.colX + band
        val cxMax = list.colX + band * 9
        val rs = list.rowSpacing.coerceAtLeast(50)
        val cyMin = list.topCy - rs * 4
        val cyMax = list.botCy + rs * 2
        val detailItems = items.filter { it.cx in cxMin..cxMax && it.cy in cyMin..cyMax }

        // Collect every clearly-parsed "N-M" id in the detail region.
        val idPattern = Regex("(\\d{1,2})\\s*[-\u2013\u2014\uFF0D]\\s*(\\d{1,2})")
        val ids = detailItems.flatMap { item ->
            idPattern.findAll(normalizeStageDigits(item.text)).mapNotNull { m ->
                val ch = m.groupValues[1].toIntOrNull()
                val lv = m.groupValues[2].toIntOrNull()
                if (ch != null && lv != null) ch to lv else null
            }
        }
        if (ids.isNotEmpty()) {
            return ids.all { it.first == TARGET_CHAPTER && it.second == TARGET_LEVEL }
        }

        // No dash-form id parsed (e.g. "1-9" misread as "19", or the leading "1" of the
        // header dropped → "-9"). Fall back to a loose id match OR the stage's distinctive
        // name. Safe because we reach here only when NO id parsed at all (so no conflicting
        // stage like 3-10 is present), and the name is unique to 1-9.
        val loose = Regex("$TARGET_CHAPTER\\s*[-\u2013\u2014\uFF0D]?\\s*$TARGET_LEVEL(?!\\d)")
        if (detailItems.any { loose.containsMatchIn(normalizeStageDigits(it.text)) }) return true
        return detailItems.any { item -> TARGET_NAME_HINTS.any { it in item.text } }
    }

    /**
     * Slow, short swipe (~2 rows) within the stage column to minimise fling overshoot.
     * Uses the detected list geometry when available, else falls back to gameWindow.
     * @param revealEarlier true to bring smaller-numbered stages into view (drag down).
     */
    private fun doStageScroll(revealEarlier: Boolean, list: StageList?) {
        val x: Float
        val yHi: Float
        val yLo: Float
        if (list != null) {
            x = list.colX.toFloat()
            val mid = (list.topCy + list.botCy) / 2f
            val half = list.rowSpacing.toFloat()  // ~1 row each way → ~2 rows total
            yHi = mid - half
            yLo = mid + half
        } else {
            val gw = gameWindow ?: return
            x = gw.absX(0.13).toFloat()
            yHi = gw.absY(0.46).toFloat()
            yLo = gw.absY(0.63).toFloat()
        }
        val durationMs = 750L
        stageScrollCount++
        val dir = if (revealEarlier) "earlier↑" else "later↓"
        log("  [STAGE] scroll #$stageScrollCount ($dir) x=${x.toInt()} y=${yHi.toInt()}..${yLo.toInt()}")

        val a11y = ClickerAccessibilityService.instance
        if (a11y != null) {
            if (revealEarlier) a11y.swipe(x, yHi, x, yLo, durationMs)
            else a11y.swipe(x, yLo, x, yHi, durationMs)
        } else if (shizukuAvailable) {
            val (y1, y2) = if (revealEarlier) yHi to yLo else yLo to yHi
            shellExec("input swipe ${x.toInt()} ${y1.toInt()} ${x.toInt()} ${y2.toInt()} $durationMs")
        } else {
            log("  [STAGE] No A11y/Shizuku — cannot scroll")
        }
        // Let the list settle before the next scan re-reads it.
        Thread.sleep(800)
    }

    private var lastGameplayDelay = GAMEPLAY_SLOW_MS

    // ── Stage-search loop state (reset whenever we leave STAGE_SELECT) ──
    private var stageTargetTapCount = 0
    private var stageScrollCount = 0
    private var stageScrollStuckCount = 0
    private var lastStageSnapshot: String? = null
    private var stageSearchAborted = false
    // Current scroll direction (true = reveal earlier/smaller stages by dragging down).
    // Remembered across frames so OCR-blind frames keep going the right way instead of
    // always scrolling up. Null = not yet established.
    private var stageRevealEarlier: Boolean? = null
    // Whether we've already flipped direction after hitting an edge this run.
    private var stageDirReversed = false

    private fun resetStageSearch() {
        stageTargetTapCount = 0
        stageScrollCount = 0
        stageScrollStuckCount = 0
        lastStageSnapshot = null
        stageSearchAborted = false
        stageRevealEarlier = null
        stageDirReversed = false
    }

    /**
     * Position-stable fingerprint of the current list view, used to tell whether a
     * scroll actually moved the list. Works even when stage ids are OCR-garbled.
     * Digits/colons are stripped so the ticking top clock doesn't fake "progress".
     */
    private fun stageSnapshot(items: List<OcrItem>, list: StageList?): String {
        val band = (minOf(captureW, captureH) * 0.06).toInt().coerceAtLeast(40)
        val src = if (list != null)
            items.filter { kotlin.math.abs(it.cx - list.colX) <= band }
        else items
        return src.sortedBy { it.cy * 10000 + it.cx }
            .joinToString("|") {
                val t = it.text.filter { c -> !c.isDigit() && c != ':' }.trim()
                "${it.cy / 20}:$t"
            }
    }

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
        // Challenge FAILED screen (挑战失败): tap "退出" to return to stage select.
        // NOT "重新挑战" (which would replay the failing stage). This happens e.g. when a
        // special-event stage with a higher revenue target got played by mistake.
        if (items.any { "失败" in it.text }) {
            val exit = items.find { "退出" in it.text }
            val gw = gameWindow
            when {
                exit != null -> tap(exit.cx, exit.cy, "exit (challenge failed)")
                gw != null -> tap(gw.absX(0.39), gw.absY(0.72), "exit failed (fallback)")
                else -> log("  [SETTLEMENT] Failed screen but no exit button / window")
            }
            return
        }

        parseRatio(items, ENERGY_TOTAL)?.let { (cur, total) ->
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

        // "领取" is often OCR-garbled in small window (e.g. "師取"), but the "取" glyph
        // survives and only appears in 领取 on this screen. Match it to tap the REAL
        // button position — robust to the rel-Y shift between window aspect ratios.
        val pos = items.find { "领取" in it.text || "領取" in it.text || "取" in it.text }
        if (pos != null) {
            tap(pos.cx, pos.cy, "collect")
        } else {
            // Truly no button text this frame (e.g. mid-animation). Tap its known rel
            // position as a last resort (aspect-dependent, so only an approximation).
            val gw = gameWindow
            if (gw != null) {
                tap(gw.absX(COLLECT_REL_X), gw.absY(COLLECT_REL_Y), "collect (fallback rel)")
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

        // Collect transforms and valid buffers with their line indices. On this ROM the
        // toDisplayTransform line comes BEFORE its geomBufferSize within a layer block,
        // so we must pair each buffer with the NEAREST transform line (either direction)
        // rather than assuming a fixed order. We also track the owning Layer header so we
        // can prefer the game's actual render surface (a SurfaceView/BLAST layer) over
        // transient/background layers that may momentarily be larger.
        data class T(val idx: Int, val sx: Double, val sy: Double, val tx: Double, val ty: Double)
        data class B(val idx: Int, val w: Int, val h: Int, val surfaceView: Boolean)
        val transforms = mutableListOf<T>()
        val buffers = mutableListOf<B>()
        val layerHeader = Regex("^\\s*Layer \\[")

        var currentIsSurfaceView = false
        lines.forEachIndexed { idx, line ->
            if (layerHeader.containsMatchIn(line)) {
                currentIsSurfaceView = line.contains("SurfaceView") || line.contains("BLAST")
            }
            transformPattern.find(line)?.let { m ->
                val sx = m.groupValues[1].toDoubleOrNull()
                val sy = m.groupValues[2].toDoubleOrNull()
                val tx = m.groupValues[3].toDoubleOrNull()
                val ty = m.groupValues[4].toDoubleOrNull()
                if (sx != null && sy != null && tx != null && ty != null && sx > 0 && sy > 0) {
                    transforms.add(T(idx, sx, sy, tx, ty))
                }
            }
            bufferPattern.find(line)?.let { m ->
                val w = m.groupValues[1].toInt()
                val h = m.groupValues[2].toInt()
                if (w > 50 && h > 50) buffers.add(B(idx, w, h, currentIsSurfaceView))
            }
        }

        // Build candidate windows by pairing each buffer with its nearest transform.
        var bestWindow: GameWindow? = null
        var bestScore = -1L  // surfaceView layers rank above non-surfaceView; then by area
        for (b in buffers) {
            val t = transforms.minByOrNull { kotlin.math.abs(it.idx - b.idx) } ?: continue
            // Require them to be in the same layer block (adjacent lines).
            if (kotlin.math.abs(t.idx - b.idx) > 2) continue

            val left = t.tx.toInt()
            val top = t.ty.toInt()
            val right = (t.tx + b.w * t.sx).toInt()
            val bottom = (t.ty + b.h * t.sy).toInt()
            val w = right - left
            val h = bottom - top
            if (w > 50 && h > 50 && left >= 0 && top >= 0 &&
                right <= physicalLong + 5 && bottom <= physicalLong + 5) {
                val area = w.toLong() * h
                // SurfaceView layers get a large bonus so they always win over
                // transient/background layers, with area as the tiebreaker.
                val score = (if (b.surfaceView) 1_000_000_000L else 0L) + area
                if (score > bestScore) {
                    bestScore = score
                    bestWindow = GameWindow(left, top, right, bottom)
                    log("  [SF] candidate(sv=${b.surfaceView}): scale=(${t.sx},${t.sy}) tx=${t.tx} ty=${t.ty} buf=${b.w}x${b.h} → ($left,$top)-($right,$bottom) ${w}x${h}")
                }
            }
        }
        if (bestWindow != null) {
            log("  [SF] Physical bounds (chosen): $bestWindow")
        }
        return bestWindow
    }

    /**
     * Parse standard AOSP/HWC `displayFrame=[l t r b]` format.
     *
     * The HWC layer list contains both the real game surface AND full-width
     * dim/background layers. The game surface is the one with a NON-EMPTY
     * `sourceCrop=[0 0 W H]` (actual rendered buffer); background/dim layers have
     * `sourceCrop=[0 0 0 0]`. Prefer cropped layers so we don't pick the oversized
     * background frame.
     */
    private fun parseDisplayFrame(output: String): GameWindow? {
        val displayFramePattern = Regex("displayFrame=\\[(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)]")
        val sourceCropPattern = Regex("sourceCrop=\\[([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)]")
        val cropped = mutableListOf<GameWindow>()
        val all = mutableListOf<GameWindow>()

        for (line in output.lines()) {
            val match = displayFramePattern.find(line) ?: continue
            val left = match.groupValues[1].toInt()
            val top = match.groupValues[2].toInt()
            val right = match.groupValues[3].toInt()
            val bottom = match.groupValues[4].toInt()
            val w = right - left
            val h = bottom - top
            if (w <= 50 || h <= 50) continue
            val win = GameWindow(left, top, right, bottom)
            all.add(win)
            val sc = sourceCropPattern.find(line)
            val hasContent = sc != null &&
                    (sc.groupValues[3].toFloatOrNull() ?: 0f) > 1f &&
                    (sc.groupValues[4].toFloatOrNull() ?: 0f) > 1f
            if (hasContent) cropped.add(win)
        }

        // Prefer real rendered surfaces; fall back to any frame if none had a crop.
        val pool = if (cropped.isNotEmpty()) cropped else all
        return pool.maxByOrNull { it.width.toLong() * it.height }
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

    /**
     * Debug: when a `dump.flag` sentinel file exists in the app's external files dir,
     * save the current captured frame as `frame_raw.png` (one-shot, deletes the flag).
     * Trigger from host with:
     *   adb shell "touch /sdcard/Android/data/<pkg>/files/dump.flag"
     * then pull files/frame_raw.png. Lets us analyse the real MediaProjection pixels
     * (adb screencap returns blank on this FLAG_SECURE game).
     */
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

    /** Dump a frame to [outName] when sentinel [flagName] exists (one-shot, deletes flag). */
    private fun dumpFrameIfFlag(bitmap: Bitmap, flagName: String, outName: String) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val flag = java.io.File(dir, flagName)
            if (!flag.exists()) return
            val out = java.io.File(dir, outName)
            java.io.FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            flag.delete()
            log("[DUMP] saved settle frame -> ${out.name}")
        } catch (e: Exception) {
            log("[DUMP] failed: ${e.message}")
        }
    }

    /**
     * Crop the left stage-list column, upscale it, and binarize to clean black-on-white
     * (dark text → black, everything else → white). Isolates the decorative stage-name
     * glyphs from the textured panel so ML Kit reads ids (esp. the "-") far better.
     * @return the processed bitmap and the upscale factor used (for coordinate mapping).
     */
    private fun preprocessStageColumn(src: Bitmap): Pair<Bitmap, Float> {
        val cropW = (src.width * STAGE_COL_FRAC).toInt().coerceIn(1, src.width)
        val crop = Bitmap.createBitmap(src, 0, 0, cropW, src.height)
        val scale = STAGE_OCR_SCALE
        val up = Bitmap.createScaledBitmap(
            crop, (cropW * scale).toInt(), (src.height * scale).toInt(), true
        )
        if (up != crop) crop.recycle()
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
            // Light fill = digit body → black ink; mid-gray panel & dark outline → white.
            px[i] = if (luma > STAGE_TEXT_LUMA) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        up.setPixels(px, 0, w, 0, 0, w, h)
        return up to scale
    }

    /**
     * Run a dedicated OCR pass over the preprocessed stage column, then invoke [onDone]
     * with the general items' left column replaced by the cleaner stage items. Always
     * calls [onDone] exactly once (falls back to the original items on any failure).
     */
    private fun runStageColumnOcr(
        src: Bitmap,
        generalItems: List<OcrItem>,
        onDone: (List<OcrItem>) -> Unit
    ) {
        val cropW = (src.width * STAGE_COL_FRAC).toInt().coerceIn(1, src.width)
        val processed: Bitmap
        val scale: Float
        try {
            val r = preprocessStageColumn(src)
            processed = r.first
            scale = r.second
        } catch (e: Exception) {
            log("  [STAGE-OCR] preprocess failed: ${e.message}")
            onDone(generalItems)
            return
        }
        recognizer.process(InputImage.fromBitmap(processed, 0))
            .addOnSuccessListener { vt ->
                val stageItems = mutableListOf<OcrItem>()
                for (block in vt.textBlocks) for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val cx = ((box.left + box.right) / 2f / scale).toInt()
                    val cy = ((box.top + box.bottom) / 2f / scale).toInt()
                    stageItems.add(OcrItem(line.text, cx, cy))
                }
                processed.recycle()
                // UNION (not replace): keep the general items so detection is never worse
                // than baseline, and add the binarized stage items. When both land in one
                // row, detectStageList's flush() prefers the dash-bearing id — so the
                // general "19" (dash eaten) loses to the binarized "1-9". Best of both.
                val merged = generalItems + stageItems
                log("  [STAGE-OCR] left-col: ${generalItems.count { it.cx < cropW }} general + ${stageItems.size} binarized")
                onDone(merged)
            }
            .addOnFailureListener { e ->
                processed.recycle()
                log("  [STAGE-OCR] failed: ${e.message}")
                onDone(generalItems)
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
                killGame()
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

    data class OcrItem(val text: String, val cx: Int, val cy: Int)
}
