package com.pupus.phone.car

import com.pupus.phone.ui.PttStyle
import com.pupus.phone.ui.PttStyles
import com.pupus.phone.voice.VoiceState

/**
 * What the Android Auto media card shows: play/pause reflects whether a turn is running, the
 * state goes in the subtitle, the reply in the title and the state colour in the artwork.
 */
data class CarMediaView(val isPlaying: Boolean, val stateRes: Int, val reply: String?, val style: PttStyle)

/** Maps the voice state to the media session. Pure (resource ids only), like [CarVoiceTexts]. */
object CarMediaTexts {
    fun of(state: VoiceState, isBridgeConnected: Boolean, reply: String): CarMediaView = CarMediaView(
        isPlaying = isPlaying(state),
        stateRes = CarVoiceTexts.stateRes(state, isBridgeConnected),
        reply = CarVoiceTexts.replyText(reply),
        style = PttStyles.of(state, isBridgeConnected),
    )

    /** A turn in progress (listening, thinking or speaking) shows the pause button. */
    fun isPlaying(state: VoiceState): Boolean = when (state) {
        VoiceState.STOPPED, VoiceState.IDLE -> false
        VoiceState.LISTENING, VoiceState.THINKING, VoiceState.SPEAKING -> true
    }

    /**
     * Play and pause are both a bubble tap, but only when they match what the card shows: a stale
     * play from the host during a turn must not interrupt it, nor a pause start a new one.
     */
    fun shouldTap(state: VoiceState, isPlayCommand: Boolean): Boolean = isPlayCommand != isPlaying(state)
}
