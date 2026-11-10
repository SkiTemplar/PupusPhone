package com.pupus.phone.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.pupus.phone.api.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synchronous wrappers over dispatchGesture; call from a worker thread, never the main thread. */
class Gestures(private val service: AccessibilityService) {

    fun tap(x: Int, y: Int): Outcome = press(x, y, TAP_MS)

    fun press(x: Int, y: Int, durationMs: Int): Outcome {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Outcome {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(path, durationMs)
    }

    private fun dispatch(path: Path, durationMs: Int): Outcome {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.set(COMPLETED)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.set(CANCELLED)
                latch.countDown()
            }
        }
        if (!service.dispatchGesture(gesture, callback, null)) {
            Log.w(TAG, "dispatchGesture rejected")
            return Outcome.Failure(500, "gesture_rejected")
        }
        if (!latch.await(durationMs + TIMEOUT_MARGIN_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Gesture timed out")
            return Outcome.Failure(504, "gesture_timeout")
        }
        return if (result.get() == COMPLETED) Outcome.ok(true) else Outcome.Failure(409, "gesture_cancelled")
    }

    private companion object {
        const val TAG = "PupusGesture"
        const val TAP_MS = 50
        const val TIMEOUT_MARGIN_MS = 3000L
        const val COMPLETED = "completed"
        const val CANCELLED = "cancelled"
    }
}
