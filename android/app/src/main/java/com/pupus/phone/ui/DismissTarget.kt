package com.pupus.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Round "✕" drawn by [DismissTarget]; grows a little while the bubble is over it. */
private class DismissCircleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }

    var isEngaged = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) ENGAGED_SCALE else 1f).scaleY(if (value) ENGAGED_SCALE else 1f)
                .setDuration(SCALE_MS).start()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = CIRCLE_DP * density / 2f
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)
        val arm = radius * CROSS_FRACTION
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, crossPaint)
    }

    companion object {
        const val CIRCLE_DP = 64f
        const val ENGAGED_SCALE = 1.25f
        const val SCALE_MS = 120L
        const val CROSS_FRACTION = 0.3f
        val FILL = Color.argb(0xCC, 0x15, 0x1B, 0x23)
    }
}

/**
 * Accessibility-overlay window with the dismiss target, shown only while the bubble is being
 * dragged. It never takes touches. Add it before the bubble so the bubble draws on top.
 */
internal class DismissTarget(context: Context, private val windowManager: WindowManager) {
    private val density = context.resources.displayMetrics.density
    private val windowPx = (DismissCircleView.CIRCLE_DP * DismissCircleView.ENGAGED_SCALE * density).toInt() + 1
    private val view = DismissCircleView(context).apply { visibility = View.GONE }
    private var isAttached = false

    private val params = WindowManager.LayoutParams(
        windowPx,
        windowPx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun attach() {
        if (isAttached) return
        try {
            windowManager.addView(view, params)
            isAttached = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot add dismiss target", e)
        }
    }

    fun detach() {
        if (!isAttached) return
        isAttached = false
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dismiss target already detached", e)
        }
    }

    /** Builds the hit zone for the current screen and shows the target at its centre. */
    fun show(): DismissZone {
        val bounds = windowManager.currentWindowMetrics.bounds
        val zone = DismissZone.forScreen(
            bounds.width(),
            bounds.height(),
            bottomOffset = (BOTTOM_OFFSET_DP * density).toInt(),
            snapRadius = (SNAP_RADIUS_DP * density).toInt(),
        )
        params.x = zone.centerX - windowPx / 2
        params.y = zone.centerY - windowPx / 2
        if (isAttached) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Dismiss target not attached", e)
            }
        }
        view.isEngaged = false
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(FADE_MS).start()
        return zone
    }

    fun setEngaged(isEngaged: Boolean) {
        view.isEngaged = isEngaged
    }

    fun hide() {
        view.isEngaged = false
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.visibility = View.GONE
    }

    private companion object {
        const val TAG = "PupusDismiss"
        const val BOTTOM_OFFSET_DP = 112f
        const val SNAP_RADIUS_DP = 100f
        const val FADE_MS = 150L
    }
}
