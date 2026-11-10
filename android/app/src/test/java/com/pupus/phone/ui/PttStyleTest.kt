package com.pupus.phone.ui

import com.pupus.phone.R
import com.pupus.phone.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Test

class PttStyleTest {
    @Test
    fun colorsFollowTheAgreedMapping() {
        assertEquals(R.color.ptt_stopped, PttStyles.of(VoiceState.STOPPED, isBridgeConnected = true).colorRes)
        assertEquals(R.color.ptt_idle, PttStyles.of(VoiceState.IDLE, isBridgeConnected = true).colorRes)
        assertEquals(R.color.ptt_listening, PttStyles.of(VoiceState.LISTENING, isBridgeConnected = true).colorRes)
        assertEquals(R.color.ptt_thinking, PttStyles.of(VoiceState.THINKING, isBridgeConnected = true).colorRes)
        assertEquals(R.color.ptt_speaking, PttStyles.of(VoiceState.SPEAKING, isBridgeConnected = true).colorRes)
    }

    @Test
    fun idleWithoutBridgeIsDisconnectedGrey() {
        val style = PttStyles.of(VoiceState.IDLE, isBridgeConnected = false)
        assertEquals(R.color.ptt_stopped, style.colorRes)
        assertEquals(R.string.state_disconnected, style.labelRes)
        assertEquals(PttMotion.NONE, style.motion)
    }

    @Test
    fun activeStatesKeepTheirColourWithoutBridge() {
        assertEquals(R.color.ptt_listening, PttStyles.of(VoiceState.LISTENING, isBridgeConnected = false).colorRes)
        assertEquals(R.color.ptt_speaking, PttStyles.of(VoiceState.SPEAKING, isBridgeConnected = false).colorRes)
    }

    @Test
    fun labelsAndMotions() {
        assertEquals(R.string.state_idle, PttStyles.READY.labelRes)
        assertEquals(R.string.state_listening, PttStyles.LISTENING.labelRes)
        assertEquals(R.string.state_thinking, PttStyles.THINKING.labelRes)
        assertEquals(R.string.state_speaking, PttStyles.SPEAKING.labelRes)
        assertEquals(R.string.state_disconnected, PttStyles.of(VoiceState.STOPPED, isBridgeConnected = true).labelRes)
        assertEquals(PttMotion.LEVEL, PttStyles.LISTENING.motion)
        assertEquals(PttMotion.SPIN, PttStyles.THINKING.motion)
        assertEquals(PttMotion.PULSE, PttStyles.SPEAKING.motion)
    }

    @Test
    fun levelIsNormalisedAndClamped() {
        assertEquals(0f, PttStyles.level(-10f), 0f)
        assertEquals(0f, PttStyles.level(-2f), 0f)
        assertEquals(0.5f, PttStyles.level(4f), 0.001f)
        assertEquals(1f, PttStyles.level(30f), 0f)
        assertEquals(0f, PttStyles.level(Float.NaN), 0f)
    }

    @Test
    fun overlaySnapsToTheNearestEdge() {
        assertEquals(10, OverlaySnap.targetX(x = 300, viewWidth = 200, screenWidth = 1280, margin = 10))
        assertEquals(1070, OverlaySnap.targetX(x = 600, viewWidth = 200, screenWidth = 1280, margin = 10))
    }

    @Test
    fun overlayStaysInsideTheScreenVertically() {
        assertEquals(10, OverlaySnap.clampY(y = -50, viewHeight = 200, screenHeight = 2772, margin = 10))
        assertEquals(2562, OverlaySnap.clampY(y = 4000, viewHeight = 200, screenHeight = 2772, margin = 10))
        assertEquals(900, OverlaySnap.clampY(y = 900, viewHeight = 200, screenHeight = 2772, margin = 10))
    }
}
