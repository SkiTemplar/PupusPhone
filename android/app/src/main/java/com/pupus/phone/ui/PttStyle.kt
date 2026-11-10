package com.pupus.phone.ui

import com.pupus.phone.R
import com.pupus.phone.voice.VoiceState

/** Animation of the push-to-talk circle for each state. */
enum class PttMotion { NONE, BREATHE, LEVEL, SPIN, PULSE }

/** Visual state of the push-to-talk button: resource ids plus the animation. */
data class PttStyle(val colorRes: Int, val iconColorRes: Int, val labelRes: Int, val motion: PttMotion)

/**
 * Colour is the main state signal: grey = disconnected or car mode off, blue = ready,
 * red = listening, orange = thinking, green = speaking. Pure (resource ids only).
 */
object PttStyles {
    private const val MIN_DB = -2f
    private const val MAX_DB = 10f

    val DISCONNECTED = PttStyle(R.color.ptt_stopped, R.color.on_dark_fill, R.string.state_disconnected, PttMotion.NONE)
    val READY = PttStyle(R.color.ptt_idle, R.color.on_dark_fill, R.string.state_idle, PttMotion.BREATHE)
    val LISTENING = PttStyle(R.color.ptt_listening, R.color.on_dark_fill, R.string.state_listening, PttMotion.LEVEL)
    val THINKING = PttStyle(R.color.ptt_thinking, R.color.on_light_fill, R.string.state_thinking, PttMotion.SPIN)
    val SPEAKING = PttStyle(R.color.ptt_speaking, R.color.on_light_fill, R.string.state_speaking, PttMotion.PULSE)

    /** Idle without a bridge cannot answer, so it shows as disconnected (grey). */
    fun of(state: VoiceState, isBridgeConnected: Boolean): PttStyle = when (state) {
        VoiceState.STOPPED -> DISCONNECTED
        VoiceState.IDLE -> if (isBridgeConnected) READY else DISCONNECTED
        VoiceState.LISTENING -> LISTENING
        VoiceState.THINKING -> THINKING
        VoiceState.SPEAKING -> SPEAKING
    }

    /** Maps SpeechRecognizer rmsdB (about -2..10) to 0..1. */
    fun level(rmsDb: Float): Float {
        if (rmsDb.isNaN()) return 0f
        return ((rmsDb - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    }
}

/** Where the floating button rests after a drag: the nearest side edge, inside the screen. */
object OverlaySnap {
    fun targetX(x: Int, viewWidth: Int, screenWidth: Int, margin: Int): Int {
        val center = x + viewWidth / 2
        return if (center < screenWidth / 2) margin else screenWidth - viewWidth - margin
    }

    fun clampY(y: Int, viewHeight: Int, screenHeight: Int, margin: Int): Int {
        val max = (screenHeight - viewHeight - margin).coerceAtLeast(margin)
        return y.coerceIn(margin, max)
    }
}
