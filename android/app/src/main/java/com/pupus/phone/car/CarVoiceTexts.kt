package com.pupus.phone.car

import com.pupus.phone.R
import com.pupus.phone.voice.VoiceState

/** Car texts: state label and reply. Pure (resource ids only), like [com.pupus.phone.ui.PttStyles]. */
object CarVoiceTexts {
    const val MAX_REPLY_CHARS = 300

    private val WHITESPACE = Regex("\\s+")

    /** Idle without a bridge cannot answer: shown as disconnected, as the bubble does. */
    fun stateRes(state: VoiceState, isBridgeConnected: Boolean): Int = when (state) {
        VoiceState.STOPPED -> R.string.state_stopped
        VoiceState.IDLE -> if (isBridgeConnected) R.string.state_idle else R.string.state_disconnected
        VoiceState.LISTENING -> R.string.state_listening
        VoiceState.THINKING -> R.string.state_thinking
        VoiceState.SPEAKING -> R.string.state_speaking
    }

    /** Last reply collapsed to one paragraph and cut for the car screen; null when there is none. */
    fun replyText(reply: String): String? {
        val text = reply.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_REPLY_CHARS) text else text.take(MAX_REPLY_CHARS - 1).trimEnd() + "…"
    }
}
