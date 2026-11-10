package com.pupus.phone.voice

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Creates the speech recognizer chosen by [RecognizerChoice]. On API 33+ it probes once
 * whether es-ES is installed for on-device recognition. Main thread only.
 */
class RecognizerProvider(private val context: Context) {
    private var isSpanishOnDevice: Boolean? = null
    private var hasOnDeviceFailed = false
    private var isProbing = false

    fun isAnyAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || isOnDeviceAvailable()

    fun choose(mode: RecognizerMode): RecognizerKind =
        RecognizerChoice.pick(mode, isOnDeviceAvailable(), isSpanishOnDevice, hasOnDeviceFailed)

    fun create(kind: RecognizerKind): SpeechRecognizer {
        val recognizer = if (kind == RecognizerKind.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        Log.i(TAG, "Using ${kind.name} recognizer (es-ES on device: $isSpanishOnDevice, failed: $hasOnDeviceFailed)")
        return recognizer
    }

    /** The on-device recognizer rejected es-ES: AUTO mode will use the default one from now on. */
    fun markOnDeviceFailed() {
        hasOnDeviceFailed = true
        Log.w(TAG, "On-device recognizer rejected $LANGUAGE_TAG; falling back to the default recognizer")
    }

    /** Asynchronously learns whether es-ES is installed on device. No-op below API 33 or if known. */
    fun probe(intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isSpanishOnDevice != null || isProbing || !isOnDeviceAvailable()) return
        probeSupport(intent)
    }

    private fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun probeSupport(intent: Intent) {
        isProbing = true
        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        probe.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                isSpanishOnDevice = support.installedOnDeviceLanguages.any { it.startsWith("es", ignoreCase = true) }
                Log.i(TAG, "On-device installed=${support.installedOnDeviceLanguages} pending=${support.pendingOnDeviceLanguages}")
                done(probe)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "checkRecognitionSupport failed: $error")
                isSpanishOnDevice = false
                done(probe)
            }
        })
    }

    private fun done(probe: SpeechRecognizer) {
        isProbing = false
        probe.destroy()
    }

    private companion object {
        const val TAG = "PupusStt"
    }
}
