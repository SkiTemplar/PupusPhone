package com.pupus.phone.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRepairPolicyTest {
    private val policy = AccessibilityRepairPolicy("com.pupus.phone", "com.pupus.phone.PhoneAccessibilityService")
    private val ours = "com.pupus.phone/com.pupus.phone.PhoneAccessibilityService"
    private val other = "com.other.app/com.other.app.ReaderService"
    private val third = "org.foo/.Svc"

    private val dead = A11ySnapshot(
        enabledServices = ours,
        isAccessibilityEnabled = true,
        isRunning = false,
        isWanted = true,
        canWriteSecureSettings = true,
        nowMs = 100_000L,
        lastAttemptMs = null,
    )

    @Test
    fun runningServiceIsLeftAlone() {
        val decision = policy.decide(dead.copy(isRunning = true))
        assertEquals(A11yOutcome.RUNNING, decision.outcome)
        assertFalse(decision.isRepair)
    }

    @Test
    fun deadServiceIsRemovedThenReAdded() {
        val decision = policy.decide(dead)
        assertEquals(A11yOutcome.REBIND, decision.outcome)
        assertEquals(listOf("", ours), decision.writes)
    }

    @Test
    fun rebindPreservesOtherServicesAndTheirOrder() {
        val decision = policy.decide(dead.copy(enabledServices = "$other:$ours:$third"))
        assertEquals(listOf("$other:$third", "$other:$third:$ours"), decision.writes)
    }

    @Test
    fun shortComponentFormIsRecognisedAndNormalised() {
        val decision = policy.decide(dead.copy(enabledServices = "$other:com.pupus.phone/.PhoneAccessibilityService"))
        assertEquals(A11yOutcome.REBIND, decision.outcome)
        assertEquals(listOf(other, "$other:$ours"), decision.writes)
    }

    @Test
    fun missingServiceIsAddedBackWhenAccessibilityIsOn() {
        val decision = policy.decide(dead.copy(enabledServices = other))
        assertEquals(A11yOutcome.ENABLE, decision.outcome)
        assertEquals(listOf("$other:$ours"), decision.writes)
    }

    @Test
    fun nullOrEmptySettingAddsOnlyOurs() {
        listOf(null, "", " : ").forEach { setting ->
            val decision = policy.decide(dead.copy(enabledServices = setting))
            assertEquals(A11yOutcome.ENABLE, decision.outcome)
            assertEquals(listOf(ours), decision.writes)
        }
    }

    @Test
    fun missingServiceStaysOffWhenAccessibilityIsOff() {
        val decision = policy.decide(dead.copy(enabledServices = null, isAccessibilityEnabled = false))
        assertEquals(A11yOutcome.OFF, decision.outcome)
        assertFalse(decision.isRepair)
    }

    @Test
    fun serviceTurnedOffByTheUserIsNotReAdded() {
        val decision = policy.decide(dead.copy(enabledServices = other, isWanted = false))
        assertEquals(A11yOutcome.OFF, decision.outcome)
    }

    @Test
    fun withoutPermissionNothingIsWritten() {
        val decision = policy.decide(dead.copy(canWriteSecureSettings = false))
        assertEquals(A11yOutcome.NEEDS_PERMISSION, decision.outcome)
        assertTrue(decision.writes.isEmpty())
    }

    @Test
    fun withoutPermissionAndOffReportsOff() {
        val decision = policy.decide(dead.copy(enabledServices = "", isAccessibilityEnabled = false, canWriteSecureSettings = false))
        assertEquals(A11yOutcome.OFF, decision.outcome)
    }

    @Test
    fun attemptsAreRateLimited() {
        val tooSoon = policy.decide(dead.copy(lastAttemptMs = dead.nowMs - 9_999L))
        assertEquals(A11yOutcome.RATE_LIMITED, tooSoon.outcome)
        assertTrue(tooSoon.writes.isEmpty())
        val later = policy.decide(dead.copy(lastAttemptMs = dead.nowMs - AccessibilityRepairPolicy.MIN_INTERVAL_MS))
        assertEquals(A11yOutcome.REBIND, later.outcome)
    }

    @Test
    fun similarComponentsOfOtherAppsAreNotOurs() {
        assertFalse(policy.isListed("com.pupus.phone2/com.pupus.phone.PhoneAccessibilityService"))
        assertFalse(policy.isListed("com.pupus.phone/com.pupus.phone.OtherService"))
        assertFalse(policy.isListed("garbage"))
        assertTrue(policy.isListed("$other:$ours"))
    }
}

class A11yDisplayTest {
    @org.junit.Test
    fun mapsOutcomesToTheStatusCard() {
        assertEquals(A11yDisplay.RUNNING, A11yDisplay.of(A11yOutcome.RUNNING, 1_000L))
        assertEquals(A11yDisplay.NO_PERMISSION, A11yDisplay.of(A11yOutcome.NEEDS_PERMISSION, null))
        assertEquals(A11yDisplay.OFF, A11yDisplay.of(A11yOutcome.OFF, null))
        assertEquals(A11yDisplay.REPAIRED, A11yDisplay.of(A11yOutcome.RATE_LIMITED, 5_000L))
        assertEquals(A11yDisplay.DEAD, A11yDisplay.of(A11yOutcome.REBIND, null))
        assertEquals(A11yDisplay.DEAD, A11yDisplay.of(A11yOutcome.ENABLE, A11yDisplay.REPAIR_WINDOW_MS))
    }
}
