package com.pupus.phone.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Long-form dictation on top of SpeechRecognizer. The recognizer ends a segment on
 * short pauses; this controller restarts it and lets [DictationSession] glue the
 * segments until a second tap, the configured silence, or the 120 s cap.
 *
 * Segmented sessions (RecognizerIntent.EXTRA_SEGMENTED_SESSION, API 33+) are not used:
 * support depends on the recognition service and their end-of-session rule is not
 * "N ms without new words", so the restart approach is the single path for every API.
 *
 * Must be used on the main thread.
 */
class SpeechInput(
    private val context: Context,
    private val provider: RecognizerProvider,
    private val cues: AudioCues,
) {
    interface Callback {
        fun onTranscript(text: String)
        fun onUtterance(text: String)
        fun onNothingHeard()
        fun onSpeechError(code: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var kind = RecognizerKind.DEFAULT
    private var mode = RecognizerMode.AUTO
    private var session: DictationSession? = null
    private var callback: Callback? = null
    private var silenceMs = DictationConfig.DEFAULT_SILENCE_MS
    private var generation = 0

    private val tick = object : Runnable {
        override fun run() {
            val current = session ?: return
            apply(current.onTick())
            if (isActive) main.postDelayed(this, TICK_MS)
        }
    }
    private val listenNow = Runnable { if (isActive) listen() }

    val isActive: Boolean get() = session?.isFinished == false

    fun isAvailable(): Boolean = provider.isAnyAvailable()

    /** Learns early whether es-ES is installed on device, so the first turn can use it. */
    fun prepare() = provider.probe(buildIntent())

    fun start(callback: Callback, config: DictationConfig, mode: RecognizerMode) {
        stopListening()
        this.callback = callback
        this.mode = mode
        silenceMs = config.silenceMs
        if (recognizer != null && provider.choose(mode) != kind) destroyRecognizer()
        session = DictationSession(config, SystemClock::elapsedRealtime).also { it.start() }
        cues.listeningStarted()
        main.postDelayed(listenNow, AudioCues.START_TONE_MS + TONE_MARGIN_MS)
        main.postDelayed(tick, TICK_MS)
        provider.probe(buildIntent())
    }

    /** Second tap: send what has been heard so far (or end quietly if nothing). */
    fun sendNow() {
        val current = session ?: return
        apply(current.onTap())
    }

    /** Ends the turn without sending anything. */
    fun cancel() {
        if (!isActive) return
        session = null
        stopListening()
        cues.release()
    }

    fun destroy() {
        cancel()
        destroyRecognizer()
    }

    private fun listen() {
        val sr = recognizer ?: createRecognizer()
        generation++
        sr.setRecognitionListener(Listener(generation))
        sr.startListening(buildIntent())
    }

    private fun createRecognizer(): SpeechRecognizer {
        kind = provider.choose(mode)
        return provider.create(kind).also { recognizer = it }
    }

    private fun destroyRecognizer() {
        generation++
        recognizer?.destroy()
        recognizer = null
    }

    /** Stops timers and the running recognizer session; late callbacks are ignored. */
    private fun stopListening() {
        main.removeCallbacks(tick)
        main.removeCallbacks(listenNow)
        generation++
        recognizer?.cancel()
    }

    private fun apply(step: DictationStep) {
        when (step) {
            DictationStep.Wait -> Unit
            DictationStep.Restart -> main.post(listenNow)
            DictationStep.Recreate -> {
                destroyRecognizer()
                main.postDelayed(listenNow, RECREATE_DELAY_MS)
            }
            is DictationStep.Send -> finish {
                cues.utteranceSent()
                it.onUtterance(step.text)
            }
            DictationStep.NothingHeard -> finish {
                cues.release()
                it.onNothingHeard()
            }
            is DictationStep.Fail -> finish {
                cues.release()
                it.onSpeechError(step.code)
            }
        }
    }

    private fun finish(deliver: (Callback) -> Unit) {
        stopListening()
        val target = callback ?: return
        callback = null
        deliver(target)
    }

    private fun onSegmentText(text: String?, isFinal: Boolean) {
        val current = session ?: return
        if (isFinal) {
            val step = current.onFinal(text.orEmpty())
            callback?.onTranscript(current.transcript)
            apply(step)
        } else if (text != null && current.onPartial(text)) {
            callback?.onTranscript(current.transcript)
        }
    }

    private fun onRecognizerError(code: Int) {
        val current = session ?: return
        Log.w(TAG, "Recognizer ($kind) error $code")
        if (RecognizerChoice.shouldFallBack(code, mode, kind)) {
            provider.markOnDeviceFailed()
            apply(DictationStep.Recreate)
            return
        }
        apply(current.onError(code))
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Hints only: many recognition services ignore them, hence the restart loop.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, silenceMs)

    private inner class Listener(private val id: Int) : RecognitionListener {
        private val isCurrent: Boolean get() = id == generation

        override fun onPartialResults(partialResults: Bundle?) {
            if (isCurrent) onSegmentText(firstResult(partialResults), isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            if (isCurrent) onSegmentText(firstResult(results), isFinal = true)
        }

        override fun onError(error: Int) {
            if (isCurrent) onRecognizerError(error)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    private companion object {
        const val TAG = "PupusStt"
        const val TICK_MS = 200L
        const val TONE_MARGIN_MS = 80L
        const val RECREATE_DELAY_MS = 250L
    }
}
