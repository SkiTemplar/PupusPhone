package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWatchdogTest {
    private fun run(start: BridgeWatchdog, ticks: List<Long>, isConnected: Boolean = false, isLoopback: Boolean = true): List<Boolean> {
        var state = start
        return ticks.map { now ->
            val step = state.tick(now, isConnected, isLoopback)
            state = step.state
            step.shouldRelaunch
        }
    }

    @Test
    fun relaunchesOnlyAfterTheGracePeriod() {
        val result = run(BridgeWatchdog(), listOf(0L, 5_000L, 10_000L, 15_000L, 15_001L))
        assertEquals(listOf(false, false, false, false, true), result)
    }

    @Test
    fun relaunchesAtMostOncePerInterval() {
        val result = run(BridgeWatchdog(), listOf(0L, 20_000L, 40_000L, 79_999L, 80_000L))
        assertEquals(listOf(false, true, false, false, true), result)
    }

    @Test
    fun carModeLaunchCountsForTheInterval() {
        val result = run(BridgeWatchdog().onLaunched(0L), listOf(0L, 20_000L, 59_999L, 60_000L))
        assertEquals(listOf(false, false, false, true), result)
    }

    @Test
    fun connectionResetsTheDownTimer() {
        val down = BridgeWatchdog().tick(0L, isConnected = false, isLoopback = true).state
        val up = down.tick(10_000L, isConnected = true, isLoopback = true).state
        assertNull(up.downSinceMs)
        assertFalse(up.tick(20_000L, isConnected = false, isLoopback = true).shouldRelaunch)
        assertFalse(up.tick(20_000L, false, true).state.tick(35_000L, false, true).shouldRelaunch)
        assertTrue(up.tick(20_000L, false, true).state.tick(35_001L, false, true).shouldRelaunch)
    }

    @Test
    fun neverRelaunchesForARemoteBridge() {
        assertEquals(listOf(false, false, false), run(BridgeWatchdog(), listOf(0L, 30_000L, 120_000L), isLoopback = false))
    }

    @Test
    fun neverRelaunchesWhileConnected() {
        assertEquals(listOf(false, false), run(BridgeWatchdog(), listOf(0L, 120_000L), isConnected = true))
    }

    @Test
    fun tickDoesNotMutateThePreviousState() {
        val start = BridgeWatchdog()
        start.tick(0L, isConnected = false, isLoopback = true)
        assertNull(start.downSinceMs)
    }
}
