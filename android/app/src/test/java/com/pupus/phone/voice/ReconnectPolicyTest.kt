package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun openWithoutReadyKeepsGrowingBackoff() {
        val policy = ReconnectPolicy(Backoff(initialSeconds = 1, maxSeconds = 30))
        assertEquals(ReconnectDecision.Retry(1), policy.onClosed(1006))
        // The socket opened but the bridge closed before `ready`: no reset.
        assertEquals(ReconnectDecision.Retry(2), policy.onClosed(1011))
        assertEquals(ReconnectDecision.Retry(4), policy.onFailure(null))
    }

    @Test
    fun readyResetsBackoff() {
        val policy = ReconnectPolicy(Backoff(initialSeconds = 1, maxSeconds = 30))
        policy.onClosed(1006)
        policy.onClosed(1006)
        policy.onReady()
        assertEquals(ReconnectDecision.Retry(1), policy.onClosed(1006))
    }

    @Test
    fun badTokenStopsRetrying() {
        val policy = ReconnectPolicy()
        assertEquals(ReconnectDecision.Rejected, policy.onClosed(1008))
        assertEquals(ReconnectDecision.Rejected, policy.onFailure(401))
        assertEquals(ReconnectDecision.Rejected, policy.onFailure(403))
    }
}
