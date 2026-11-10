package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRequestsTest {

    @Test
    fun resetIsSentNowWhenConnected() {
        val pending = PendingRequests()
        assertTrue(pending.requestReset(isConnected = true))
        assertFalse(pending.isResetPending)
        assertEquals(OnConnect(shouldReset = false, shouldTap = false), pending.onConnected())
    }

    @Test
    fun resetWhileDisconnectedIsSentOnConnect() {
        val pending = PendingRequests()
        assertFalse(pending.requestReset(isConnected = false))
        assertTrue(pending.isResetPending)
        assertEquals(OnConnect(shouldReset = true, shouldTap = false), pending.onConnected())
        assertFalse(pending.isResetPending)
    }

    @Test
    fun pendingResetSurvivesFailedAttemptsButTapDoesNot() {
        val pending = PendingRequests()
        pending.requestReset(isConnected = false)
        pending.queueTap()
        assertTrue(pending.onConnectFailed())
        assertFalse(pending.onConnectFailed())
        assertEquals(OnConnect(shouldReset = true, shouldTap = false), pending.onConnected())
    }

    @Test
    fun deferredResetAndTapRunOnConnect() {
        val pending = PendingRequests()
        pending.deferReset()
        pending.queueTap()
        assertEquals(OnConnect(shouldReset = true, shouldTap = true), pending.onConnected())
        assertEquals(OnConnect(shouldReset = false, shouldTap = false), pending.onConnected())
    }

    @Test
    fun clearDropsEverything() {
        val pending = PendingRequests()
        pending.requestReset(isConnected = false)
        pending.queueTap()
        pending.clear()
        assertEquals(OnConnect(shouldReset = false, shouldTap = false), pending.onConnected())
    }
}
