package com.pupus.phone.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

internal const val LANGUAGE_TAG = "es-ES"

/**
 * Queued es-ES TextToSpeech. [onDrained] runs on the main thread when the current
 * turn has nothing left to say; [onFailed] runs once if the engine cannot start.
 * Must be used from the main thread.
 */
class SpeechOutput(
    context: Context,
    private val onDrained: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private enum class EngineState { INITIALIZING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val tracker = UtteranceTracker()
    private val backlog = ArrayList<String>()
    private var engineState = EngineState.INITIALIZING
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }

    private fun onInit(status: Int) {
        if (engineState != EngineState.INITIALIZING) return
        if (status != TextToSpeech.SUCCESS) {
            fail("TTS init failed: $status")
            return
        }
        val result = tts.setLanguage(Locale.forLanguageTag(LANGUAGE_TAG))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "es-ES voice not available ($result)")
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(ProgressListener())
        engineState = EngineState.READY
        val queued = backlog.toList()
        backlog.clear()
        queued.forEach { speakNow(it) }
    }

    /** Drops queued text so the turn can end instead of staying in SPEAKING forever. */
    private fun fail(reason: String) {
        Log.e(TAG, reason)
        engineState = EngineState.FAILED
        val hadBacklog = backlog.isNotEmpty()
        backlog.clear()
        onFailed()
        if (hadBacklog) onDrained()
    }

    fun isSpeaking(): Boolean = tracker.isBusy || backlog.isNotEmpty()

    fun enqueue(text: String) {
        if (text.isBlank()) return
        when (engineState) {
            EngineState.INITIALIZING -> backlog.add(text)
            EngineState.READY -> speakNow(text)
            EngineState.FAILED -> {
                Log.w(TAG, "TTS unavailable; not speaking ${text.length} chars")
                main.post { onDrained() }
            }
        }
    }

    /** Ends the current turn's speech; late callbacks of its utterances are ignored. */
    fun stop() {
        backlog.clear()
        tracker.newTurn()
        if (engineState == EngineState.READY) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun speakNow(text: String) {
        val id = tracker.next()
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed for $id")
            onUtteranceFinished(id)
        }
    }

    private fun onUtteranceFinished(id: String?) {
        if (tracker.finish(id)) onDrained()
    }

    private inner class ProgressListener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            main.post { onUtteranceFinished(utteranceId) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            main.post { onUtteranceFinished(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS error on $utteranceId")
            main.post { onUtteranceFinished(utteranceId) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "TTS error $errorCode on $utteranceId")
            main.post { onUtteranceFinished(utteranceId) }
        }
    }

    private companion object {
        const val TAG = "PupusTts"
    }
}
