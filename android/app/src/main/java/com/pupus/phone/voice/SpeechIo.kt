package com.pupus.phone.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

internal const val LANGUAGE_TAG = "es-ES"

/**
 * Queued es-ES TextToSpeech. [onDrained] runs on the main thread when the current
 * turn has nothing left to say; [onFailed] runs when the engine cannot be (re)started.
 * Plays as USAGE_ASSISTANT + CONTENT_TYPE_SPEECH (the media volume, as the Google Assistant)
 * at [volume] (TextToSpeech KEY_PARAM_VOLUME, 0.0 to 1.0, read for every utterance).
 *
 * The engine runs in another process that the ROM may kill at any time (HyperOS does, under
 * memory pressure). TextToSpeech does not always rebind: `speak` then fails, or the queued
 * utterances get no callback at all. Both cases recreate the engine and queue the unspoken
 * text again, a few times per minute at most ([EngineRestartPolicy], [SpeechStallDetector]).
 * Must be used from the main thread.
 */
class SpeechOutput(
    context: Context,
    private val onDrained: () -> Unit,
    private val onFailed: () -> Unit,
    private val volume: () -> Float = { 1.0f },
) {
    private enum class EngineState { INITIALIZING, READY, FAILED }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tracker = UtteranceTracker()
    private val stall = SpeechStallDetector()
    private val restarts = EngineRestartPolicy()
    private val backlog = ArrayList<String>()
    private var engineState = EngineState.INITIALIZING
    private var engineGeneration = 0
    private var tts: TextToSpeech = createEngine()
    private val stallCheck = object : Runnable {
        override fun run() = checkStall()
    }

    private fun createEngine(): TextToSpeech {
        val generation = ++engineGeneration
        engineState = EngineState.INITIALIZING
        return TextToSpeech(app) { status -> main.post { if (generation == engineGeneration) onInit(status) } }
    }

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
        tts.setOnUtteranceProgressListener(ProgressListener(engineGeneration))
        engineState = EngineState.READY
        val queued = backlog.toList()
        backlog.clear()
        // A speak failure restarts the engine: the rest waits in the backlog, in order.
        for (text in queued) {
            if (engineState == EngineState.READY) speakNow(text) else backlog.add(text)
        }
    }

    /** Drops queued text so the turn can end instead of staying in SPEAKING forever. */
    private fun fail(reason: String) {
        Log.e(TAG, reason)
        engineState = EngineState.FAILED
        val hadText = backlog.isNotEmpty() || tracker.isBusy
        backlog.clear()
        tracker.newTurn()
        stall.reset()
        main.removeCallbacks(stallCheck)
        onFailed()
        if (hadText) onDrained()
    }

    fun isSpeaking(): Boolean = tracker.isBusy || backlog.isNotEmpty()

    fun enqueue(text: String) {
        if (text.isBlank()) return
        when (engineState) {
            EngineState.INITIALIZING -> backlog.add(text)
            EngineState.READY -> speakNow(text)
            EngineState.FAILED -> {
                // A previous failure may have been transient (engine killed): try a fresh engine.
                backlog.add(text)
                restartEngine("engine marked failed")
            }
        }
    }

    /** Ends the current turn's speech; late callbacks of its utterances are ignored. */
    fun stop() {
        backlog.clear()
        tracker.newTurn()
        stall.reset()
        main.removeCallbacks(stallCheck)
        if (engineState == EngineState.READY) tts.stop()
    }

    fun shutdown() {
        stop()
        engineGeneration++
        tts.shutdown()
    }

    private fun speakNow(text: String) {
        val wasBusy = tracker.isBusy
        val id = tracker.next(text)
        stall.onQueued(SystemClock.elapsedRealtime(), wasBusy)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume().coerceIn(0f, 1f)) }
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        if (result != TextToSpeech.SUCCESS) {
            restartEngine("speak returned $result for $id")
            return
        }
        scheduleStallCheck()
    }

    /**
     * Replaces a lost engine: the unfinished utterances go back to the backlog, in order, and are
     * spoken once the new engine is ready. Gives up (FAILED) when it keeps failing.
     */
    private fun restartEngine(reason: String) {
        val retry = tracker.takeForRetry()
        backlog.addAll(0, retry)
        stall.reset()
        main.removeCallbacks(stallCheck)
        if (!restarts.tryRestart(SystemClock.elapsedRealtime())) {
            fail("TTS engine lost ($reason); restart limit reached")
            return
        }
        Log.w(TAG, "TTS engine lost ($reason); restarting it with ${backlog.size} queued")
        engineGeneration++
        try {
            tts.shutdown()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Shutdown of the lost TTS engine failed", e)
        }
        tts = createEngine()
    }

    private fun scheduleStallCheck() {
        main.removeCallbacks(stallCheck)
        main.postDelayed(stallCheck, STALL_CHECK_MS)
    }

    private fun checkStall() {
        if (engineState != EngineState.READY || !tracker.isBusy) return
        if (stall.isStalled(SystemClock.elapsedRealtime(), tracker.isBusy)) {
            restartEngine("no progress callback")
            return
        }
        main.postDelayed(stallCheck, STALL_CHECK_MS)
    }

    private fun onUtteranceStarted(id: String?) {
        if (tracker.owns(id)) stall.onStart(SystemClock.elapsedRealtime())
    }

    private fun onUtteranceFinished(id: String?) {
        if (!tracker.owns(id)) return
        stall.onFinished(SystemClock.elapsedRealtime())
        if (!tracker.finish(id)) return
        stall.reset()
        main.removeCallbacks(stallCheck)
        onDrained()
    }

    private fun onUtteranceError(id: String?, code: Int) {
        if (TtsErrors.isEngineLost(code) && tracker.owns(id)) {
            restartEngine("error $code on $id")
            return
        }
        onUtteranceFinished(id)
    }

    /** Callbacks of a replaced engine ([generation] no longer current) are ignored. */
    private inner class ProgressListener(private val generation: Int) : UtteranceProgressListener() {
        private fun onMain(action: () -> Unit) {
            main.post { if (generation == engineGeneration) action() }
        }

        override fun onStart(utteranceId: String?) = onMain { onUtteranceStarted(utteranceId) }

        override fun onDone(utteranceId: String?) = onMain { onUtteranceFinished(utteranceId) }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = onMain { onUtteranceFinished(utteranceId) }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS error on $utteranceId")
            onMain { onUtteranceFinished(utteranceId) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "TTS error $errorCode on $utteranceId")
            onMain { onUtteranceError(utteranceId, errorCode) }
        }
    }

    private companion object {
        const val TAG = "PupusTts"
        const val STALL_CHECK_MS = 1_000L
    }
}
