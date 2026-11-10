package com.pupus.phone.ui

import android.view.MotionEvent
import com.pupus.phone.voice.VoiceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTouchPolicyTest {
    @Test
    fun bubbleIgnoresTouchesWhileATurnIsRunning() {
        assertFalse(OverlayTouchPolicy.acceptsTouches(VoiceState.THINKING, gesturesInFlight = 0))
        assertFalse(OverlayTouchPolicy.acceptsTouches(VoiceState.SPEAKING, gesturesInFlight = 0))
    }

    @Test
    fun bubbleIgnoresTouchesDuringAnyDeviceApiGesture() {
        VoiceState.entries.forEach { state ->
            assertFalse("$state", OverlayTouchPolicy.acceptsTouches(state, gesturesInFlight = 1))
        }
    }

    @Test
    fun bubbleTakesTouchesOtherwise() {
        listOf(VoiceState.STOPPED, VoiceState.IDLE, VoiceState.LISTENING).forEach { state ->
            assertTrue("$state", OverlayTouchPolicy.acceptsTouches(state, gesturesInFlight = 0))
        }
    }

    @Test
    fun acceptsARealFingerOrStylus() {
        assertTrue(OverlayTouchPolicy.isRealFinger(7, 0, MotionEvent.TOOL_TYPE_FINGER, isFromTouchscreen = true))
        assertTrue(OverlayTouchPolicy.isRealFinger(7, 0, MotionEvent.TOOL_TYPE_STYLUS, isFromTouchscreen = true))
    }

    @Test
    fun rejectsInjectedOrSyntheticTouches() {
        val finger = MotionEvent.TOOL_TYPE_FINGER
        val a11y = OverlayTouchPolicy.FLAG_IS_ACCESSIBILITY_EVENT
        assertFalse(OverlayTouchPolicy.isRealFinger(7, a11y, finger, isFromTouchscreen = true))
        assertFalse(OverlayTouchPolicy.isRealFinger(0, 0, finger, isFromTouchscreen = true))
        assertFalse(OverlayTouchPolicy.isRealFinger(-1, 0, finger, isFromTouchscreen = true))
        assertFalse(OverlayTouchPolicy.isRealFinger(7, 0, MotionEvent.TOOL_TYPE_MOUSE, isFromTouchscreen = true))
        assertFalse(OverlayTouchPolicy.isRealFinger(7, 0, finger, isFromTouchscreen = false))
    }
}
