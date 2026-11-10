package com.pupus.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BindPolicyTest {
    private val interfaces = listOf(
        InterfaceAddress("tun0", "100.101.102.103"),
        InterfaceAddress("wlan0", "192.168.1.40"),
        InterfaceAddress("rmnet_data0", "10.20.30.40"),
    )

    @Test
    fun defaultBindsOnlyLoopbackAndTailscale() {
        assertEquals(setOf("127.0.0.1", "100.101.102.103"), BindPolicy.select(interfaces, isLanAllowed = false))
    }

    @Test
    fun neverBindsWildcard() {
        val selected = BindPolicy.select(interfaces, isLanAllowed = true)
        assertFalse("0.0.0.0" in selected)
    }

    @Test
    fun lanSettingAddsOnlyWifiAddress() {
        assertEquals(
            setOf("127.0.0.1", "100.101.102.103", "192.168.1.40"),
            BindPolicy.select(interfaces, isLanAllowed = true),
        )
    }

    @Test
    fun withoutTailscaleOnlyLoopback() {
        val noVpn = listOf(InterfaceAddress("wlan0", "192.168.1.40"))
        assertEquals(setOf("127.0.0.1"), BindPolicy.select(noVpn, isLanAllowed = false))
    }

    @Test
    fun tailscaleRangeIsCgnatSlash10() {
        assertTrue(BindPolicy.isTailscale("100.64.0.1"))
        assertTrue(BindPolicy.isTailscale("100.127.255.254"))
        assertFalse(BindPolicy.isTailscale("100.128.0.1"))
        assertFalse(BindPolicy.isTailscale("100.63.0.1"))
        assertFalse(BindPolicy.isTailscale("not-an-ip"))
    }

    @Test
    fun planStartsNewAndStopsGoneAddresses() {
        val plan = BindPlan.between(setOf("127.0.0.1", "100.64.0.9"), setOf("127.0.0.1", "100.64.0.10"))
        assertEquals(setOf("100.64.0.10"), plan.toStart)
        assertEquals(setOf("100.64.0.9"), plan.toStop)
    }
}
