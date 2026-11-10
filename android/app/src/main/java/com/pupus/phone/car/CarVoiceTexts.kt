package com.pupus.phone.car

import com.pupus.phone.R
import com.pupus.phone.voice.VoiceState

/** What the Android Auto screen shows: resource ids for the state and the button, and the reply. */
data class CarVoiceView(val stateRes: Int, val actionRes: Int, val reply: String?)

/** Maps the voice state to the car screen texts. Pure (resource ids only), like [com.pupus.phone.ui.PttStyles]. */
object CarVoiceTexts {
    const val MAX_REPLY_CHARS = 300

    private val WHITESPACE = Regex("\\s+")

    fun of(state: VoiceState, isBridgeConnected: Boolean, reply: String): CarVoiceView =
        CarVoiceView(stateRes(state, isBridgeConnected), actionRes(state), replyText(reply))

    /** Idle without a bridge cannot answer: shown as disconnected, as the bubble does. */
    fun stateRes(state: VoiceState, isBridgeConnected: Boolean): Int = when (state) {
        VoiceState.STOPPED -> R.string.state_stopped
        VoiceState.IDLE -> if (isBridgeConnected) R.string.state_idle else R.string.state_disconnected
        VoiceState.LISTENING -> R.string.state_listening
        VoiceState.THINKING -> R.string.state_thinking
        VoiceState.SPEAKING -> R.string.state_speaking
    }

    /** The button does what a bubble tap does in each state ([com.pupus.phone.voice.VoiceService.onTap]). */
    fun actionRes(state: VoiceState): Int = when (state) {
        VoiceState.STOPPED, VoiceState.IDLE -> R.string.car_action_talk
        VoiceState.LISTENING -> R.string.car_action_send
        VoiceState.THINKING, VoiceState.SPEAKING -> R.string.car_action_interrupt
    }

    /** Last reply collapsed to one paragraph and cut for the car screen; null when there is none. */
    fun replyText(reply: String): String? {
        val text = reply.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_REPLY_CHARS) text else text.take(MAX_REPLY_CHARS - 1).trimEnd() + "…"
    }
}
