package com.pupus.phone.voice

import android.speech.SpeechRecognizer

/** User setting: which speech recognizer to use. */
enum class RecognizerMode {
    /** On-device when available and Spanish is installed; otherwise the default service. */
    AUTO,

    /** Force SpeechRecognizer.createOnDeviceSpeechRecognizer (falls back if unavailable). */
    ON_DEVICE,

    /** Force the system default recognition service. */
    DEFAULT;

    companion object {
        fun parse(value: String?): RecognizerMode = entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class RecognizerKind { ON_DEVICE, DEFAULT }

/** Pure recognizer selection rules. */
object RecognizerChoice {
    private val LANGUAGE_ERRORS = setOf(
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    )

    /**
     * @param isOnDeviceAvailable SpeechRecognizer.isOnDeviceRecognitionAvailable (false below API 31).
     * @param isSpanishOnDevice es-ES installed on device; null while unknown (or below API 33).
     * @param hasOnDeviceFailed the on-device recognizer already failed with a language error.
     */
    fun pick(
        mode: RecognizerMode,
        isOnDeviceAvailable: Boolean,
        isSpanishOnDevice: Boolean?,
        hasOnDeviceFailed: Boolean,
    ): RecognizerKind = when {
        !isOnDeviceAvailable -> RecognizerKind.DEFAULT
        mode == RecognizerMode.ON_DEVICE -> RecognizerKind.ON_DEVICE
        mode == RecognizerMode.AUTO && isSpanishOnDevice == true && !hasOnDeviceFailed -> RecognizerKind.ON_DEVICE
        else -> RecognizerKind.DEFAULT
    }

    /** In AUTO, a language error from the on-device recognizer switches to the default one. */
    fun shouldFallBack(code: Int, mode: RecognizerMode, kind: RecognizerKind): Boolean =
        mode == RecognizerMode.AUTO && kind == RecognizerKind.ON_DEVICE && code in LANGUAGE_ERRORS
}
