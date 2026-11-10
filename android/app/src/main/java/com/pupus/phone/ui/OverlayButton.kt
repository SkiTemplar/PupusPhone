package com.pupus.phone.ui

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.pupus.phone.AppSettings
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
 * In car mode it grows to 150 dp, except over a navigation app or while the assistant shows
 * something it opened ([BubbleSizePolicy], [ShowingTracker]); the resize keeps the side edge.
 * While dragging, a ✕ target appears at the bottom; dropping the bubble on it turns car
 * mode off and hides the bubble until car mode is started again.
 * It takes no touches while a device API gesture is in flight, and only real finger touches
 * (see [OverlayTouchPolicy]): the assistant's own taps must not start listening, while a real
 * tap during THINKING or SPEAKING interrupts the turn.
 */
class OverlayButton(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val smallPx = (WINDOW_DP * density).toInt()
    private val largePx = (LARGE_WINDOW_DP * density).toInt()
    private val sizePx: Int get() = params.width
    private val marginPx = (MARGIN_DP * density).toInt()
    private var view: PttCircleView? = null
    private var snapAnimator: ValueAnimator? = null
    private var resizeAnimator: ValueAnimator? = null
    private var resizeTarget = 0
    private var foregroundPackage: String? = null
    private var showing = ShowingTracker()
    private val refreshSize = Runnable { updateSize() }
    private var state = VoiceState.STOPPED
    private var isBridgeConnected = false
    private val dismissTarget = DismissTarget(service, windowManager)
    private var dismissZone: DismissZone? = null
    private var isOverTarget = false

    /** Dropped on the dismiss target: hidden until car mode starts again. */
    private var isDismissed = false

    /** Late events from the stopping service (e.g. IDLE after TTS) must not bring the bubble back. */
    private var hasStoppedSinceDismiss = false
    private val listener = VoiceBus.Listener { newState, _ ->
        state = newState
        showing = showing.onVoiceState(newState, SystemClock.elapsedRealtime())
        if (newState == VoiceState.STOPPED) hasStoppedSinceDismiss = true
        else if (hasStoppedSinceDismiss) isDismissed = false
        render()
    }
    private val detailsListener = VoiceBus.DetailsListener { isConnected, _ ->
        isBridgeConnected = isConnected
        render()
    }
    private val levelListener = VoiceBus.LevelListener { rmsDb -> view?.setLevel(rmsDb) }
    private val updateVisibility = Runnable {
        view?.visibility = if (AppForeground.isVisible || isDismissed) View.GONE else View.VISIBLE
    }

    /** Debounced: switching between two app screens must not flash the overlay. */
    private val foregroundListener = {
        view?.removeCallbacks(updateVisibility)
        view?.postDelayed(updateVisibility, VISIBILITY_DELAY_MS)
        Unit
    }

    private val params = WindowManager.LayoutParams(
        smallPx,
        smallPx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidth() - smallPx - marginPx
        y = service.resources.displayMetrics.heightPixels / 3
    }

    fun show() {
        if (view != null) return
        val button = PttCircleView(service).apply { circleFraction = CIRCLE_DP / WINDOW_DP }
        attachTouch(button)
        // Added first so the bubble is drawn above the target when dropped on it.
        dismissTarget.attach()
        try {
            windowManager.addView(button, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot add overlay", e)
            dismissTarget.detach()
            return
        }
        view = button
        updateVisibility.run()
        OverlayTouchGate.setListener(::applyTouchability)
        AppForeground.addListener(foregroundListener)
        VoiceBus.addListener(listener)
        VoiceBus.addDetailsListener(detailsListener)
        VoiceBus.addLevelListener(levelListener)
    }

    fun hide() {
        OverlayTouchGate.setListener(null)
        AppForeground.removeListener(foregroundListener)
        VoiceBus.removeListener(listener)
        VoiceBus.removeDetailsListener(detailsListener)
        VoiceBus.removeLevelListener(levelListener)
        snapAnimator?.cancel()
        resizeAnimator?.cancel()
        view?.removeCallbacks(refreshSize)
        endDrag()
        dismissTarget.detach()
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
        applyTouchability()
        updateSize()
    }

    /** App in front, from TYPE_WINDOW_STATE_CHANGED (main thread). */
    fun onForegroundApp(packageName: String, isLauncher: Boolean) {
        foregroundPackage = packageName
        showing = showing.onForeground(packageName, isLauncher)
        updateSize()
    }

    /** Animates the window to the size the policy asks for; deferred while the finger drags it. */
    private fun updateSize() {
        val current = view ?: return
        current.removeCallbacks(refreshSize)
        val now = SystemClock.elapsedRealtime()
        showing.expiresAtMs()?.let { at -> if (at > now) current.postDelayed(refreshSize, at - now + EXPIRY_SLACK_MS) }
        if (dismissZone != null || snapAnimator?.isRunning == true) return
        val navigation = BubbleSizePolicy.parsePackages(AppSettings(service).navigationPackages)
        val size = BubbleSizePolicy.of(state != VoiceState.STOPPED, foregroundPackage, showing.isShowing(now), navigation)
        val target = if (size == BubbleSize.LARGE) largePx else smallPx
        val isResizing = resizeAnimator?.isRunning == true
        if (if (isResizing) target == resizeTarget else target == params.width) return
        resizeAnimator?.cancel()
        resizeTarget = target
        animateSize(current, target)
    }

    private fun animateSize(v: View, target: Int) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val fromSize = params.width
        val fromX = params.x
        val fromY = params.y
        resizeAnimator = ValueAnimator.ofInt(fromSize, target).apply {
            duration = RESIZE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val size = it.animatedValue as Int
                val pos = BubbleResize.place(fromX, fromY, fromSize, size, bounds.width(), bounds.height(), marginPx)
                params.width = size
                params.height = size
                move(v, pos.x, pos.y)
            }
            start()
        }
    }

    /** Toggles FLAG_NOT_TOUCHABLE: untouchable, injected taps go to the app below. True if it changed. */
    private fun applyTouchability(): Boolean {
        val current = view ?: return false
        val accepts = OverlayTouchPolicy.acceptsTouches(OverlayTouchGate.gesturesInFlight)
        val flags = if (accepts) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == params.flags) return false
        params.flags = flags
        if (!accepts) endDrag()
        if (!current.isAttachedToWindow) return true
        try {
            windowManager.updateViewLayout(current, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay not attached while changing touchability", e)
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(button: View) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var isDragging = false
        var isIgnored = false
        button.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) isIgnored = !isRealFinger(event)
            if (isIgnored) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    resizeAnimator?.cancel()
                    startX = event.rawX
                    startY = event.rawY
                    originX = params.x
                    originY = params.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > slop || abs(dy) > slop)) {
                        isDragging = true
                        dismissZone = dismissTarget.show()
                    }
                    if (isDragging) dragTo(v, BubblePosition(originX + dx.toInt(), originY + dy.toInt()))
                }
                MotionEvent.ACTION_UP -> when {
                    !isDragging -> VoiceLauncher.tap(service)
                    isOverTarget -> dismiss(v, BubblePosition(originX, originY))
                    else -> release(v)
                }
                MotionEvent.ACTION_CANCEL -> if (isDragging) release(v)
            }
            true
        }
    }

    private fun isRealFinger(event: MotionEvent): Boolean {
        val isReal = OverlayTouchPolicy.isRealFinger(
            deviceId = event.deviceId,
            flags = event.flags,
            toolType = event.getToolType(0),
            isFromTouchscreen = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN),
        )
        if (!isReal) Log.i(TAG, "Ignoring synthetic touch (device ${event.deviceId}, flags ${event.flags})")
        return isReal
    }

    /** Follows the finger, pulled onto the dismiss target when close enough. */
    private fun dragTo(v: View, raw: BubblePosition) {
        val placement = dismissZone?.place(raw, sizePx) ?: DragPlacement(raw, isOverTarget = false)
        if (placement.isOverTarget != isOverTarget) {
            isOverTarget = placement.isOverTarget
            dismissTarget.setEngaged(isOverTarget)
            if (isOverTarget) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        move(v, placement.position.x, placement.position.y)
    }

    private fun release(v: View) {
        endDrag()
        snap(v)
    }

    /** Same as turning the car mode switch off; the bubble goes back to where the drag started. */
    private fun dismiss(v: View, restore: BubblePosition) {
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        endDrag()
        isDismissed = true
        hasStoppedSinceDismiss = state == VoiceState.STOPPED
        updateVisibility.run()
        move(v, restore.x, restore.y)
        VoiceLauncher.stop(service)
    }

    private fun endDrag() {
        dismissZone = null
        isOverTarget = false
        dismissTarget.hide()
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
            doOnEnd { updateSize() }
            start()
        }
    }

    /** Runs [action] when the animation ends on its own, not when cancelled. */
    private fun ValueAnimator.doOnEnd(action: () -> Unit) = addListener(object : AnimatorListenerAdapter() {
        private var isCancelled = false

        override fun onAnimationCancel(animation: Animator) {
            isCancelled = true
        }

        override fun onAnimationEnd(animation: Animator) {
            if (!isCancelled) action()
        }
    })

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

        /** Car mode: a 150 dp circle, same glow ratio as the small one. */
        const val LARGE_WINDOW_DP = WINDOW_DP * 150f / CIRCLE_DP
        const val RESIZE_MS = 260L
        const val EXPIRY_SLACK_MS = 50L
        const val MARGIN_DP = 4f
        const val STOPPED_ALPHA = 0.7f
        const val SNAP_MS = 220L
        const val VISIBILITY_DELAY_MS = 150L
    }
}
