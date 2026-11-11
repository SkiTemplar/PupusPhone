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
}
