package com.pupus.phone.car

import com.pupus.phone.R
import com.pupus.phone.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarVoiceTextsTest {
    @Test
    fun mapsEachStateToItsLabel() {
        assertEquals(R.string.state_stopped, CarVoiceTexts.stateRes(VoiceState.STOPPED, isBridgeConnected = true))
        assertEquals(R.string.state_idle, CarVoiceTexts.stateRes(VoiceState.IDLE, isBridgeConnected = true))
        assertEquals(R.string.state_listening, CarVoiceTexts.stateRes(VoiceState.LISTENING, isBridgeConnected = true))
        assertEquals(R.string.state_thinking, CarVoiceTexts.stateRes(VoiceState.THINKING, isBridgeConnected = true))
        assertEquals(R.string.state_speaking, CarVoiceTexts.stateRes(VoiceState.SPEAKING, isBridgeConnected = true))
    }

    @Test
    fun idleWithoutBridgeIsDisconnected() {
        assertEquals(R.string.state_disconnected, CarVoiceTexts.stateRes(VoiceState.IDLE, isBridgeConnected = false))
    }

    @Test
    fun theButtonSaysWhatATapDoes() {
        assertEquals(R.string.car_action_talk, CarVoiceTexts.actionRes(VoiceState.STOPPED))
        assertEquals(R.string.car_action_talk, CarVoiceTexts.actionRes(VoiceState.IDLE))
        assertEquals(R.string.car_action_send, CarVoiceTexts.actionRes(VoiceState.LISTENING))
        assertEquals(R.string.car_action_interrupt, CarVoiceTexts.actionRes(VoiceState.THINKING))
        assertEquals(R.string.car_action_interrupt, CarVoiceTexts.actionRes(VoiceState.SPEAKING))
    }

    @Test
    fun replyIsCollapsedAndEmptyMeansNone() {
        assertEquals("Son las cinco. Hace sol.", CarVoiceTexts.replyText("  Son las cinco.\n\n Hace sol. "))
        assertNull(CarVoiceTexts.replyText(" \n "))
    }

    @Test
    fun longRepliesAreCut() {
        val text = CarVoiceTexts.replyText("a".repeat(1_000))!!
        assertEquals(CarVoiceTexts.MAX_REPLY_CHARS, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun combinesTheThree() {
        val view = CarVoiceTexts.of(VoiceState.THINKING, isBridgeConnected = true, reply = "")
        assertEquals(CarVoiceView(R.string.state_thinking, R.string.car_action_interrupt, null), view)
    }
}
