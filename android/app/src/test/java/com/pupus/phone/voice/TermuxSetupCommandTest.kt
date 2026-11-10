package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSetupCommandTest {
    private val deviceToken = "0123456789abcdef".repeat(4)
    private val bridgeToken = "a1b2c3d4e5f60718293a4b5c"

    @Test
    fun buildsTheCurlPipeToBashCommand() {
        assertEquals(
            "curl -fsSL 'https://raw.githubusercontent.com/SkiTemplar/PupusPhone/main/scripts/termux-setup.sh'" +
                " | bash -s -- --device-token $deviceToken --bridge-token $bridgeToken",
            TermuxSetupCommand.build(deviceToken, bridgeToken),
        )
    }

    @Test
    fun usesACustomScriptUrl() {
        val command = TermuxSetupCommand.build(deviceToken, bridgeToken, "https://example.org/fork/termux-setup.sh")
        assertTrue(command.startsWith("curl -fsSL 'https://example.org/fork/termux-setup.sh' | bash -s -- "))
    }

    @Test
    fun rejectsMalformedDeviceTokens() {
        listOf("", "abc", deviceToken.uppercase(), deviceToken.dropLast(1) + "g", "$deviceToken;rm").forEach {
            assertThrows(IllegalArgumentException::class.java) { TermuxSetupCommand.build(it, bridgeToken) }
        }
    }

    @Test
    fun rejectsBridgeTokensThatNeedQuotingOrAreTooShort() {
        listOf("short", "a".repeat(15), "abcdefghijklmnop qr", "abcdefghijklmnop\$(id)", "abcdefghijklmnop'x").forEach {
            assertThrows(IllegalArgumentException::class.java) { TermuxSetupCommand.build(deviceToken, it) }
        }
    }

    @Test
    fun rejectsNonHttpsOrQuotedScriptUrls() {
        listOf("http://example.org/s.sh", "https://example.org/s.sh' ; id '", "https://a b").forEach {
            assertThrows(IllegalArgumentException::class.java) { TermuxSetupCommand.build(deviceToken, bridgeToken, it) }
        }
    }

    @Test
    fun keepsAUsableBridgeToken() {
        assertEquals(bridgeToken, TermuxSetupCommand.ensureBridgeToken("  $bridgeToken ") { error("not called") })
    }

    @Test
    fun generatesABridgeTokenWhenEmptyOrUnusable() {
        assertEquals("f".repeat(48), TermuxSetupCommand.ensureBridgeToken("") { "f".repeat(48) })
        assertEquals("f".repeat(48), TermuxSetupCommand.ensureBridgeToken("has spaces in it ok") { "f".repeat(48) })
    }

    @Test
    fun generatedBridgeTokensAreUsableAndDistinct() {
        val first = TermuxSetupCommand.generateBridgeToken()
        assertEquals(48, first.length)
        assertTrue(TermuxSetupCommand.isUsableBridgeToken(first))
        assertFalse(first == TermuxSetupCommand.generateBridgeToken())
    }

    @Test
    fun loopbackUrlIsAValidBridgeBase() {
        assertTrue(VoiceUrl.isValidBase(TermuxSetupCommand.LOOPBACK_BRIDGE_URL))
    }
}
