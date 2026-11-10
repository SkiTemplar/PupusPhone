package com.pupus.phone.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.view.Display
import com.pupus.phone.api.Outcome
import com.pupus.phone.api.ShotParams
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/** takeScreenshot (API 30+) -> scaled JPEG in base64. Call from a worker thread. */
class ScreenCapture(private val service: AccessibilityService) {

    fun take(params: ShotParams): Outcome {
        val latch = CountDownLatch(1)
        val bitmap = AtomicReference<Bitmap?>(null)
        val failure = AtomicReference<String?>(null)
        val isAbandoned = AtomicBoolean(false)
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        if (hw == null) failure.set("wrap_failed") else bitmap.set(hw.copy(Bitmap.Config.ARGB_8888, false))
                        hw?.recycle()
                    }
                    // Late delivery after a timeout: nobody will encode this bitmap.
                    if (isAbandoned.get()) bitmap.getAndSet(null)?.recycle()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    failure.set("code $errorCode")
                    latch.countDown()
                }
            },
        )
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            isAbandoned.set(true)
            bitmap.getAndSet(null)?.recycle()
            return Outcome.Failure(504, "screenshot_timeout")
        }
        val full = bitmap.get()
        if (full == null) {
            Log.w(TAG, "Screenshot failed: ${failure.get()}")
            return Outcome.Failure(503, "screenshot_failed: ${failure.get()}")
        }
        return encode(full, params)
    }

    private fun encode(full: Bitmap, params: ShotParams): Outcome {
        val width = max(1, (full.width * params.scale).roundToInt())
        val height = max(1, (full.height * params.scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(full, width, height, true)
        val bytes = ByteArrayOutputStream()
        val isEncoded = scaled.compress(Bitmap.CompressFormat.JPEG, params.quality, bytes)
        if (scaled !== full) scaled.recycle()
        full.recycle()
        if (!isEncoded) return Outcome.Failure(500, "jpeg_encode_failed")
        return Outcome.Ok(
            JSONObject()
                .put("mime", "image/jpeg")
                .put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                .put("width", width)
                .put("height", height),
        )
    }

    private companion object {
        const val TAG = "PupusShot"
        const val TIMEOUT_MS = 5000L
    }
}
