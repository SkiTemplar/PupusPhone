package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecoveryTest {

    @Test
    fun restartPolicyAllowsUpToTheLimitWithinTheWindow() {
        val policy = EngineRestartPolicy(maxRestarts = 2, windowMs = 60_000)
        assertTrue(policy.tryRestart(0))
        assertTrue(policy.tryRestart(1_000))
        assertFalse(policy.tryRestart(2_000))
    }

    @Test
    fun restartPolicyForgetsRestartsOutsideTheWindow() {
        val policy = EngineRestartPolicy(maxRestarts = 2, windowMs = 60_000)
        assertTrue(policy.tryRestart(0))
        assertTrue(policy.tryRestart(1_000))
        assertTrue(policy.tryRestart(61_500))
    }

    @Test
    fun stallWhenQueuedUtteranceNeverStarts() {
        val stall = SpeechStallDetector(startTimeoutMs = 8_000, utteranceTimeoutMs = 30_000)
        stall.onQueued(nowMs = 100_000, wasBusy = false)
        assertFalse(stall.isStalled(nowMs = 107_000, isBusy = true))
        assertTrue(stall.isStalled(nowMs = 108_500, isBusy = true))
    }

    @Test
    fun playingUtteranceGetsTheLongTimeout() {
        val stall = SpeechStallDetector(startTimeoutMs = 8_000, utteranceTimeoutMs = 30_000)
        stall.onQueued(nowMs = 0, wasBusy = false)
        stall.onStart(nowMs = 500)
        assertFalse(stall.isStalled(nowMs = 20_000, isBusy = true))
        assertTrue(stall.isStalled(nowMs = 31_000, isBusy = true))
    }

    @Test
    fun finishedUtteranceRestartsTheClockForTheNextOne() {
        val stall = SpeechStallDetector(startTimeoutMs = 8_000, utteranceTimeoutMs = 30_000)
        stall.onQueued(nowMs = 0, wasBusy = false)
        stall.onStart(nowMs = 500)
        stall.onFinished(nowMs = 20_000)
        assertFalse(stall.isStalled(nowMs = 27_000, isBusy = true))
        assertTrue(stall.isStalled(nowMs = 28_500, isBusy = true))
    }

    @Test
    fun queueingBehindABusyQueueKeepsTheLastProgress() {
        val stall = SpeechStallDetector(startTimeoutMs = 8_000, utteranceTimeoutMs = 30_000)
        stall.onQueued(nowMs = 0, wasBusy = false)
        stall.onStart(nowMs = 100)
        stall.onQueued(nowMs = 25_000, wasBusy = true)
        assertTrue(stall.isStalled(nowMs = 30_500, isBusy = true))
    }

    @Test
    fun staleProgressFromAnEarlierTurnDoesNotTrigger() {
        val stall = SpeechStallDetector(startTimeoutMs = 8_000, utteranceTimeoutMs = 30_000)
        stall.onQueued(nowMs = 0, wasBusy = false)
        stall.onFinished(nowMs = 1_000)
        stall.onQueued(nowMs = 600_000, wasBusy = false)
        assertFalse(stall.isStalled(nowMs = 601_000, isBusy = true))
    }

    @Test
    fun idleQueueIsNeverStalled() {
        val stall = SpeechStallDetector()
        stall.onQueued(nowMs = 0, wasBusy = false)
        assertFalse(stall.isStalled(nowMs = 1_000_000, isBusy = false))
    }

    @Test
    fun onlyServiceErrorsMeanTheEngineIsLost() {
        assertTrue(TtsErrors.isEngineLost(-4))
        assertTrue(TtsErrors.isEngineLost(-9))
        assertFalse(TtsErrors.isEngineLost(-3))
        assertFalse(TtsErrors.isEngineLost(-8))
    }

    @Test
    fun takeForRetryReturnsUnfinishedTextsInOrder() {
        val tracker = UtteranceTracker()
        val first = tracker.next("¿Envío a Mamá: llego en diez minutos?")
        tracker.next("Dime sí o no.")
        tracker.finish(first)
        tracker.next("Tercera.")
        assertEquals(listOf("Dime sí o no.", "Tercera."), tracker.takeForRetry())
        assertFalse(tracker.isBusy)
    }

    @Test
    fun callbacksOfRetriedUtterancesAreIgnored() {
        val tracker = UtteranceTracker()
        val old = tracker.next("Hola.")
        tracker.takeForRetry()
        val fresh = tracker.next("Hola.")
        assertFalse(tracker.owns(old))
        assertFalse(tracker.finish(old))
        assertTrue(tracker.isBusy)
        assertTrue(tracker.finish(fresh))
    }
}
