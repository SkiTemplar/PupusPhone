package com.pupus.phone.ui

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTouchPolicyTest {
    @Test
    fun aRealTapMayInterruptARunningTurn() {
        // THINKING and SPEAKING take touches again: a finger tap cancels the turn (VoiceService.onTap).
        assertTrue(OverlayTouchPolicy.acceptsTouches(gesturesInFlight = 0))
    }

    @Test
    fun bubbleIgnoresTouchesDuringAnyDeviceApiGesture() {
        assertFalse(OverlayTouchPolicy.acceptsTouches(gesturesInFlight = 1))
        assertFalse(OverlayTouchPolicy.acceptsTouches(gesturesInFlight = 3))
    }

    @Test
    fun anUnbalancedReleaseNeverLocksTheBubble() {
        assertTrue(OverlayTouchPolicy.acceptsTouches(gesturesInFlight = -1))
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
