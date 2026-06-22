package com.shawtung.mobileclicker

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.provider.Settings

/**
 * Shared overlay bubble with radial gradient circle design.
 * - Running: green gradient
 * - Paused: yellow gradient
 * - Tap to toggle pause
 * - Long press to stop
 */
object BubbleHelper {

    private const val BUBBLE_SIZE_DP = 48
    private const val LONG_PRESS_TIMEOUT = 800L

    fun createBubble(
        context: Context,
        onTogglePause: (paused: Boolean) -> Unit,
        onLongPressStop: () -> Unit
    ): Pair<View, WindowManager.LayoutParams>? {
        if (!Settings.canDrawOverlays(context)) return null

        val density = context.resources.displayMetrics.density
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()

        val view = object : View(context) {
            var isPaused = false
                set(value) {
                    field = value
                    invalidate()
                }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val radius = minOf(cx, cy)

                val centerColor: Int
                val edgeColor: Int
                if (isPaused) {
                    // Yellow gradient (traffic light amber)
                    centerColor = 0xFFFFEB3B.toInt()
                    edgeColor = 0xFFF9A825.toInt()
                } else {
                    // Green gradient (traffic light green)
                    centerColor = 0xFF66BB6A.toInt()
                    edgeColor = 0xFF2E7D32.toInt()
                }

                val gradient = RadialGradient(
                    cx, cy, radius,
                    centerColor, edgeColor,
                    Shader.TileMode.CLAMP
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = gradient
                }
                canvas.drawCircle(cx, cy, radius, paint)

                // Draw subtle border
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * density
                    color = 0x40000000
                }
                canvas.drawCircle(cx, cy, radius - density, borderPaint)
            }
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Top-RIGHT corner: the in-game exit icon sits at the top-LEFT of the game
            // window, so keep the bubble on the right so it never covers it.
            gravity = Gravity.TOP or Gravity.END
            x = 0
            // Sit near the very top so the bubble doesn't cover the topmost stage
            // list item's name in the level-select screen.
            y = 24
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var downX = 0f
        var downY = 0f
        var downParamX = 0
        var downParamY = 0
        var moved = false
        var longPressed = false

        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                onLongPressStop()
            }
        }

        view.setOnTouchListener { v, event ->
            try {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downParamX = params.x
                        downParamY = params.y
                        moved = false
                        longPressed = false
                        v.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dx * dx + dy * dy > 25) {
                            moved = true
                            v.removeCallbacks(longPressRunnable)
                        }
                        // Gravity is END (right-anchored): a larger x moves the bubble
                        // LEFT, so negate the horizontal delta to keep drag intuitive.
                        params.x = downParamX - dx.toInt()
                        params.y = downParamY + dy.toInt()
                        wm.updateViewLayout(v, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPressRunnable)
                        if (!moved && !longPressed) {
                            view.isPaused = !view.isPaused
                            onTogglePause(view.isPaused)
                        }
                        true
                    }
                    else -> false
                }
            } catch (_: Exception) {
                true
            }
        }

        return Pair(view, params)
    }
}

