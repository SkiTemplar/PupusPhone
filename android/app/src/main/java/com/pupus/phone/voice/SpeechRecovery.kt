package com.pupus.phone.voice

/**
 * Limits how often the TextToSpeech engine is recreated: at most [maxRestarts] within [windowMs].
 * A broken engine (no voice installed, engine disabled) must not loop forever.
 * Not thread-safe: use from one thread.
 */
class EngineRestartPolicy(
    private val maxRestarts: Int = DEFAULT_MAX_RESTARTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private var recent: List<Long> = emptyList()

    /** Records a restart at [nowMs] and returns true, or returns false when the limit is reached. */
    fun tryRestart(nowMs: Long): Boolean {
        val kept = recent.filter { nowMs - it < windowMs }
        if (kept.size >= maxRestarts) {
            recent = kept
            return false
        }
        recent = kept + nowMs
        return true
    }

    companion object {
        const val DEFAULT_MAX_RESTARTS = 3
        const val DEFAULT_WINDOW_MS = 60_000L
    }
}

/**
 * Detects a TextToSpeech engine that stopped answering. When the engine process dies (HyperOS
 * kills `com.google.android.tts` under memory pressure), the queued utterances get no callback
 * at all: without this the turn would stay in SPEAKING, silent, until the next tap.
 * - Nothing playing: the next utterance must start within [startTimeoutMs] of the last progress.
 * - One playing: it must end within [utteranceTimeoutMs] (sentences are spoken one by one).
 * Not thread-safe: use from one thread.
 */
class SpeechStallDetector(
    private val startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
    private val utteranceTimeoutMs: Long = DEFAULT_UTTERANCE_TIMEOUT_MS,
) {
    private var lastProgressMs: Long? = null
    private var isPlaying = false

    /** An utterance was queued; [wasBusy] tells whether others were already pending. */
    fun onQueued(nowMs: Long, wasBusy: Boolean) {
        if (!wasBusy || lastProgressMs == null) lastProgressMs = nowMs
    }

    fun onStart(nowMs: Long) {
        isPlaying = true
        lastProgressMs = nowMs
    }

    /** Done, stopped or error: the engine is alive and moves to the next utterance. */
    fun onFinished(nowMs: Long) {
        isPlaying = false
        lastProgressMs = nowMs
    }

    fun reset() {
        lastProgressMs = null
        isPlaying = false
    }

    fun isStalled(nowMs: Long, isBusy: Boolean): Boolean {
        if (!isBusy) return false
        val since = lastProgressMs ?: return false
        val limit = if (isPlaying) utteranceTimeoutMs else startTimeoutMs
        return nowMs - since > limit
    }

    companion object {
        const val DEFAULT_START_TIMEOUT_MS = 8_000L
        const val DEFAULT_UTTERANCE_TIMEOUT_MS = 30_000L
    }
}

/** TextToSpeech error codes that mean the engine connection is gone, not a bad sentence. */
object TtsErrors {
    /** TextToSpeech.ERROR_SERVICE: the connection to the engine failed. */
    const val ERROR_SERVICE = -4

    /** TextToSpeech.ERROR_NOT_INSTALLED_YET: the voice data is still being installed. */
    const val ERROR_NOT_INSTALLED_YET = -9

    fun isEngineLost(code: Int): Boolean = code == ERROR_SERVICE || code == ERROR_NOT_INSTALLED_YET
}
