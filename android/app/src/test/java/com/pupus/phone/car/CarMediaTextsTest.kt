package com.pupus.phone.car

import com.pupus.phone.R
import com.pupus.phone.ui.PttStyles
import com.pupus.phone.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaTextsTest {
    @Test
    fun onlyATurnInProgressShowsAsPlaying() {
        assertFalse(CarMediaTexts.isPlaying(VoiceState.STOPPED))
        assertFalse(CarMediaTexts.isPlaying(VoiceState.IDLE))
        assertTrue(CarMediaTexts.isPlaying(VoiceState.LISTENING))
        assertTrue(CarMediaTexts.isPlaying(VoiceState.THINKING))
        assertTrue(CarMediaTexts.isPlaying(VoiceState.SPEAKING))
    }

    @Test
    fun playTapsOnlyWhenNoTurnIsRunning() {
        assertTrue(CarMediaTexts.shouldTap(VoiceState.IDLE, isPlayCommand = true))
        assertTrue(CarMediaTexts.shouldTap(VoiceState.STOPPED, isPlayCommand = true))
        assertFalse(CarMediaTexts.shouldTap(VoiceState.LISTENING, isPlayCommand = true))
        assertFalse(CarMediaTexts.shouldTap(VoiceState.SPEAKING, isPlayCommand = true))
    }

    @Test
    fun pauseTapsOnlyDuringATurn() {
        assertFalse(CarMediaTexts.shouldTap(VoiceState.IDLE, isPlayCommand = false))
        assertFalse(CarMediaTexts.shouldTap(VoiceState.STOPPED, isPlayCommand = false))
        assertTrue(CarMediaTexts.shouldTap(VoiceState.LISTENING, isPlayCommand = false))
        assertTrue(CarMediaTexts.shouldTap(VoiceState.THINKING, isPlayCommand = false))
        assertTrue(CarMediaTexts.shouldTap(VoiceState.SPEAKING, isPlayCommand = false))
    }

    @Test
    fun viewCarriesStateReplyAndColour() {
        val view = CarMediaTexts.of(VoiceState.SPEAKING, isBridgeConnected = true, reply = "  Hola\n\nmundo ")
        assertTrue(view.isPlaying)
        assertEquals(R.string.state_speaking, view.stateRes)
        assertEquals("Hola mundo", view.reply)
        assertEquals(PttStyles.SPEAKING, view.style)
    }

    @Test
    fun idleWithoutBridgeIsGreyAndDisconnected() {
        val view = CarMediaTexts.of(VoiceState.IDLE, isBridgeConnected = false, reply = "")
        assertFalse(view.isPlaying)
        assertEquals(R.string.state_disconnected, view.stateRes)
        assertNull(view.reply)
        assertEquals(PttStyles.DISCONNECTED, view.style)
    }
}
