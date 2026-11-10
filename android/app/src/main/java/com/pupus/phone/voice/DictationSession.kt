package com.pupus.phone.voice

import android.speech.SpeechRecognizer

/** Timing rules of one long-form dictation turn. Times are milliseconds. */
data class DictationConfig(
    /** Continuous silence (no new words) after which the turn is sent ("Pausa para enviar"). */
    val silenceMs: Long = DEFAULT_SILENCE_MS,
    /** If nothing at all is heard within this time, the turn ends without sending. */
    val noSpeechMs: Long = 8_000,
    /** Hard cap per turn: whatever was heard is sent. */
    val maxMs: Long = 120_000,
    /** Consecutive recognizer recreations allowed without a clean segment end in between. */
    val maxRecreates: Int = 3,
) {
    companion object {
        const val MIN_SILENCE_MS = 1_000L
        const val MAX_SILENCE_MS = 6_000L
        const val DEFAULT_SILENCE_MS = 2_500L

        fun clampSilence(ms: Long): Long = ms.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS)
    }
}

/** What the recognizer controller must do next. */
sealed interface DictationStep {
    /** Keep the current recognizer session running (or nothing to do). */
    object Wait : DictationStep

    /** The recognizer session ended early: start listening again on the same instance. */
    object Restart : DictationStep

    /** The recognizer is in a bad state: destroy it, create a new one and listen again. */
    object Recreate : DictationStep

    /** Turn finished: send the accumulated text as one utterance. */
    data class Send(val text: String) : DictationStep

    /** Turn finished without hearing anything: end quietly. */
    object NothingHeard : DictationStep

    /** Turn finished with an unrecoverable recognizer error and nothing heard. */
    data class Fail(val code: Int) : DictationStep
}

/**
 * Accumulates recognizer segments into one turn and decides when the turn ends.
 * Android's SpeechRecognizer closes a segment on short pauses; the controller restarts
 * it and this class glues the segments until the user taps again, stays silent for
 * [DictationConfig.silenceMs], or [DictationConfig.maxMs] is reached.
 *
 * Pure (no Android calls besides error constants) and driven by an injected [clock].
 * Not thread-safe: use from one thread.
 */
class DictationSession(private val config: DictationConfig, private val clock: () -> Long) {
    private val committed = ArrayList<String>()
    private var partial = ""
    private var startedAt = 0L
    private var lastWordsAt = 0L
    private var hasHeard = false
    private var recreateStreak = 0

    var isFinished = false
        private set

    /** Committed segments plus the in-progress partial, separated by single spaces. */
    val transcript: String
        get() = (committed + partial).filter { it.isNotEmpty() }.joinToString(" ")

    fun start() {
        committed.clear()
        partial = ""
        startedAt = clock()
        lastWordsAt = startedAt
        hasHeard = false
        recreateStreak = 0
        isFinished = false
    }

    /** Returns true when the visible transcript changed. */
    fun onPartial(text: String): Boolean {
        if (isFinished) return false
        val words = normalize(text)
        if (words.isEmpty() || words == partial) return false
        partial = words
        markWords()
        return true
    }

    /** The current segment ended with a final result. */
    fun onFinal(text: String): DictationStep {
        if (isFinished) return DictationStep.Wait
        val words = normalize(text).ifEmpty { partial }
        val isDuplicate = partial.isEmpty() && words == committed.lastOrNull()
        val bringsNewWords = words.isNotEmpty() && words != partial && !isDuplicate
        partial = ""
        if (words.isNotEmpty() && !isDuplicate) committed.add(words)
        if (bringsNewWords) markWords()
        recreateStreak = 0
        return deadlineStep() ?: DictationStep.Restart
    }

    /** The current segment ended with a recognizer error. */
    fun onError(code: Int): DictationStep {
        if (isFinished) return DictationStep.Wait
        deadlineStep()?.let { return it }
        return when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                recreateStreak = 0
                DictationStep.Restart
            }
            in RECREATE_ERRORS -> {
                recreateStreak++
                if (recreateStreak > config.maxRecreates) fatal(code) else DictationStep.Recreate
            }
            else -> fatal(code)
        }
    }

    /** Periodic check while the recognizer is running. */
    fun onTick(): DictationStep {
        if (isFinished) return DictationStep.Wait
        return deadlineStep() ?: DictationStep.Wait
    }

    /** Second tap: "enviar ya". */
    fun onTap(): DictationStep {
        if (isFinished) return DictationStep.Wait
        return finishWithTranscript()
    }

    private fun deadlineStep(): DictationStep? {
        val now = clock()
        val elapsed = now - startedAt
        if (!hasHeard) {
            return if (elapsed >= config.noSpeechMs || elapsed >= config.maxMs) finish(DictationStep.NothingHeard) else null
        }
        val isSilent = now - lastWordsAt >= config.silenceMs
        return if (isSilent || elapsed >= config.maxMs) finishWithTranscript() else null
    }

    private fun fatal(code: Int): DictationStep =
        if (hasHeard) finishWithTranscript() else finish(DictationStep.Fail(code))

    private fun finishWithTranscript(): DictationStep {
        val text = transcript
        return finish(if (text.isEmpty()) DictationStep.NothingHeard else DictationStep.Send(text))
    }

    private fun finish(step: DictationStep): DictationStep {
        isFinished = true
        return step
    }

    private fun markWords() {
        lastWordsAt = clock()
        hasHeard = true
        recreateStreak = 0
    }

    private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val RECREATE_ERRORS = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
    }
}
