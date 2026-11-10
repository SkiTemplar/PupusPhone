package com.pupus.phone.voice

/** Media stream volume operations used by [MediaVolumeBoost]; the Android implementation wraps AudioManager. */
interface MediaVolume {
    val current: Int
    val min: Int
    val max: Int

    /** Returns false when the platform refused the change. */
    fun set(index: Int): Boolean
}

/** "Volumen de la voz" settings, in percent, and their conversion to engine values. */
object VoiceVolumeConfig {
    const val DEFAULT_TTS_PERCENT = 100

    /** 0 disables the automatic raise of the media volume (the default). */
    const val DEFAULT_MIN_MEDIA_PERCENT = 0
    const val MAX_MIN_MEDIA_PERCENT = 100

    fun clampPercent(percent: Int): Int = percent.coerceIn(0, 100)

    /** TextToSpeech.Engine.KEY_PARAM_VOLUME: 0.0 to 1.0. */
    fun ttsVolume(percent: Int): Float = clampPercent(percent) / 100f

    /**
     * Media index the stream has to reach so the voice is at least [minPercent] of the range,
     * or null when it is already loud enough or the raise is disabled.
     */
    fun boostTarget(current: Int, min: Int, max: Int, minPercent: Int): Int? {
        val percent = clampPercent(minPercent)
        if (percent == 0 || max <= min) return null
        val target = min + Math.ceil((max - min) * percent / 100.0).toInt()
        return if (current < target) target.coerceAtMost(max) else null
    }
}

/**
 * Raises the media volume while the assistant speaks and puts it back afterwards. The volume
 * is only restored if nobody changed it during the turn (the user's own change wins).
 * [raise] and [restore] are idempotent. Not thread-safe: use from one thread.
 */
class MediaVolumeBoost(private val volume: MediaVolume) {
    private var original: Int? = null
    private var boosted: Int? = null

    val isBoosting: Boolean get() = boosted != null

    fun raise(minPercent: Int) {
        if (boosted != null) return
        val before = volume.current
        val target = VoiceVolumeConfig.boostTarget(before, volume.min, volume.max, minPercent) ?: return
        if (!volume.set(target)) return
        original = before
        boosted = target
    }

    fun restore() {
        val target = boosted ?: return
        val before = original
        boosted = null
        original = null
        if (before != null && volume.current == target) volume.set(before)
    }
}

/**
 * Volume side of a voice turn. The recognizer earcon mute (media, notification, system) must
 * never survive into speech: the assistant voice follows the media volume, so a stream left
 * muted would make it inaudible. Main thread only.
 */
class TurnAudio(private val muter: StreamMuter, private val boost: MediaVolumeBoost) {
    /** Call before every utterance of the assistant. */
    fun beforeSpeech(minMediaPercent: Int) {
        muter.restore()
        boost.raise(minMediaPercent)
    }

    /** Speech finished, interrupted or the turn ended: everything back as the user left it. */
    fun release() {
        muter.restore()
        boost.restore()
    }
}
