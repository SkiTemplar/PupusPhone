package com.pupus.phone.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * When the push-to-talk bubble may take touches. The bubble floats above every app, so a tap
 * the assistant injects with dispatchGesture at the same spot would land on it and start a new
 * listening turn (interrupting the running one). Hence:
 * - no touches while a device API gesture is in flight (the window steps aside for each one);
 * - only touches from a real finger or stylus on the touchscreen.
 * A real finger may still tap while THINKING or SPEAKING: that interrupts the turn (cancel).
 */
object OverlayTouchPolicy {
    /** MotionEvent flag set by the input dispatcher on events injected by accessibility (hidden in the SDK). */
    const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800

    fun acceptsTouches(gesturesInFlight: Int): Boolean = gesturesInFlight <= 0

    /**
     * Injected gestures come with device id 0 (or the virtual device, -1) and, from Android 12,
     * with [FLAG_IS_ACCESSIBILITY_EVENT]; real touchscreens have a positive device id.
     */
    fun isRealFinger(deviceId: Int, flags: Int, toolType: Int, isFromTouchscreen: Boolean): Boolean =
        deviceId > 0 &&
            flags and FLAG_IS_ACCESSIBILITY_EVENT == 0 &&
            isFromTouchscreen &&
            (toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_STYLUS)
}

/**
 * Process-wide count of device API gestures in flight, so the overlay can step aside before
 * each one is dispatched. [beginGesture] and [endGesture] run on worker threads; the overlay
 * listener runs on the main thread and returns true when it changed the window.
 */
object OverlayTouchGate {
    private const val APPLY_TIMEOUT_MS = 300L
    private const val SETTLE_MS = 80L
    private const val RELEASE_DELAY_MS = 150L

    private val main = Handler(Looper.getMainLooper())
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var listener: (() -> Boolean)? = null

    val gesturesInFlight: Int get() = inFlight.get()

    fun setListener(newListener: (() -> Boolean)?) {
        listener = newListener
    }

    /**
     * Blocks until the overlay has applied the change (bounded), then lets the window manager
     * update its input windows before the gesture is injected. Never call it on the main thread.
     */
    fun beginGesture() {
        inFlight.incrementAndGet()
        val applied = CountDownLatch(1)
        var hasChanged = false
        main.post {
            hasChanged = listener?.invoke() == true
            applied.countDown()
        }
        if (applied.await(APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS) && hasChanged) Thread.sleep(SETTLE_MS)
    }

    /** The bubble comes back a little later: the injected ACTION_UP may still be in the input queue. */
    fun endGesture() {
        main.postDelayed({
            inFlight.decrementAndGet()
            listener?.invoke()
        }, RELEASE_DELAY_MS)
    }
}
