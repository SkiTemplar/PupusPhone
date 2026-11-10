package com.pupus.phone.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.pupus.phone.voice.VoiceBus
import com.pupus.phone.voice.VoiceLauncher
import com.pupus.phone.voice.VoiceState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Tracks whether a PupusPhone screen is in front, so the overlay does not cover it. Main thread only. */
object AppForeground {
    private var resumed = 0
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isVisible: Boolean get() = resumed > 0

    fun onResume() {
        resumed++
        listeners.forEach { it() }
    }

    fun onPause() {
        resumed = (resumed - 1).coerceAtLeast(0)
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * Draggable round push-to-talk button drawn as TYPE_ACCESSIBILITY_OVERLAY, so it floats
 * over any app (e.g. Spotify) without SYSTEM_ALERT_WINDOW. Same circle as the main
 * screen (72 dp plus glow room); it snaps to the nearest side edge when released.
 */
class OverlayButton(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val sizePx = (WINDOW_DP * density).toInt()
    private val marginPx = (MARGIN_DP * density).toInt()
    private var view: PttCircleView? = null
    private var snapAnimator: ValueAnimator? = null
    private var state = VoiceState.STOPPED
    private var isBridgeConnected = false
    private val listener = VoiceBus.Listener { newState, _ ->
        state = newState
        render()
    }
    private val detailsListener = VoiceBus.DetailsListener { isConnected, _ ->
        isBridgeConnected = isConnected
        render()
    }
    private val levelListener = VoiceBus.LevelListener { rmsDb -> view?.setLevel(rmsDb) }
    private val updateVisibility = Runnable {
        view?.visibility = if (AppForeground.isVisible) View.GONE else View.VISIBLE
    }

    /** Debounced: switching between two app screens must not flash the overlay. */
    private val foregroundListener = {
        view?.removeCallbacks(updateVisibility)
        view?.postDelayed(updateVisibility, VISIBILITY_DELAY_MS)
        Unit
    }

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidth() - sizePx - marginPx
        y = service.resources.displayMetrics.heightPixels / 3
    }

    fun show() {
        if (view != null) return
        val button = PttCircleView(service).apply { circleFraction = CIRCLE_DP / WINDOW_DP }
        attachTouch(button)
        try {
            windowManager.addView(button, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot add overlay", e)
            return
        }
        view = button
        updateVisibility.run()
        AppForeground.addListener(foregroundListener)
        VoiceBus.addListener(listener)
        VoiceBus.addDetailsListener(detailsListener)
        VoiceBus.addLevelListener(levelListener)
    }

    fun hide() {
        AppForeground.removeListener(foregroundListener)
        VoiceBus.removeListener(listener)
        VoiceBus.removeDetailsListener(detailsListener)
        VoiceBus.removeLevelListener(levelListener)
        snapAnimator?.cancel()
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay already detached", e)
        }
    }

    private fun render() {
        val current = view ?: return
        current.render(PttStyles.of(state, isBridgeConnected))
        current.alpha = if (state == VoiceState.STOPPED) STOPPED_ALPHA else 1f
        // Car mode keeps the screen on through this window while another app is in front.
        current.keepScreenOn = state != VoiceState.STOPPED
        updateVisibility.run()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(button: View) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var isDragging = false
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = event.rawX
                    startY = event.rawY
                    originX = params.x
                    originY = params.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > slop || abs(dy) > slop)) isDragging = true
                    if (isDragging) move(v, originX + dx.toInt(), originY + dy.toInt())
                }
                MotionEvent.ACTION_UP -> if (isDragging) snap(v) else VoiceLauncher.tap(service)
                MotionEvent.ACTION_CANCEL -> if (isDragging) snap(v)
            }
            true
        }
    }

    private fun snap(v: View) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val targetX = OverlaySnap.targetX(params.x, sizePx, bounds.width(), marginPx)
        val targetY = OverlaySnap.clampY(params.y, sizePx, bounds.height(), marginPx)
        val fromX = params.x
        val fromY = params.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                move(v, fromX + ((targetX - fromX) * f).toInt(), fromY + ((targetY - fromY) * f).toInt())
            }
            start()
        }
    }

    private fun move(v: View, x: Int, y: Int) {
        params.x = x
        params.y = y
        if (!v.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay not attached while moving", e)
        }
    }

    private fun screenWidth(): Int = windowManager.currentWindowMetrics.bounds.width()

    private companion object {
        const val TAG = "PupusOverlay"
        const val CIRCLE_DP = 72f
        const val WINDOW_DP = 96f
        const val MARGIN_DP = 4f
        const val STOPPED_ALPHA = 0.7f
        const val SNAP_MS = 220L
        const val VISIBILITY_DELAY_MS = 150L
    }
}
