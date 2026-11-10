package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxLaunchPolicyTest {
    private val ready = TermuxEnvironment(
        isEnabled = true,
        bridgeUrl = "ws://127.0.0.1:8766/voice",
        scriptPath = TermuxRunCommand.DEFAULT_SCRIPT_PATH,
        isTermuxInstalled = true,
        isPermissionGranted = true,
    )

    @Test
    fun launchesWhenEverythingIsInPlace() {
        assertEquals(TermuxStatus.READY, TermuxLaunchPolicy.status(ready))
        assertTrue(TermuxLaunchPolicy.shouldLaunch(ready))
    }

    @Test
    fun doesNotLaunchWhenDisabled() {
        val env = ready.copy(isEnabled = false)
        assertEquals(TermuxStatus.DISABLED, TermuxLaunchPolicy.status(env))
        assertFalse(TermuxLaunchPolicy.shouldLaunch(env))
    }

    @Test
    fun doesNotLaunchForRemoteBridge() {
        val env = ready.copy(bridgeUrl = "ws://100.64.0.1:8766/voice")
        assertEquals(TermuxStatus.NOT_LOOPBACK, TermuxLaunchPolicy.status(env))
        assertFalse(TermuxLaunchPolicy.shouldLaunch(env))
    }

    @Test
    fun reportsMissingTermuxBeforeMissingPermission() {
        val env = ready.copy(isTermuxInstalled = false, isPermissionGranted = false)
        assertEquals(TermuxStatus.NOT_INSTALLED, TermuxLaunchPolicy.status(env))
        assertFalse(TermuxLaunchPolicy.shouldLaunch(env))
    }

    @Test
    fun reportsMissingPermission() {
        val env = ready.copy(isPermissionGranted = false)
        assertEquals(TermuxStatus.PERMISSION_MISSING, TermuxLaunchPolicy.status(env))
        assertFalse(TermuxLaunchPolicy.shouldLaunch(env))
    }

    @Test
    fun reportsInvalidScriptPath() {
        listOf("", "  ", "scripts/termux-start.sh", "/", "~/").forEach { path ->
            assertEquals(path, TermuxStatus.INVALID_PATH, TermuxLaunchPolicy.status(ready.copy(scriptPath = path)))
        }
        assertTrue(TermuxLaunchPolicy.isValidScriptPath("~/PupusPhone/scripts/termux-start.sh"))
    }

    @Test
    fun recognisesLoopbackHosts() {
        listOf(
            "ws://127.0.0.1:8766/voice",
            "ws://127.1.2.3:8766/voice",
            "ws://localhost:8766/voice",
            "WS://LOCALHOST/voice",
            "ws://[::1]:8766/voice",
            "wss://127.0.0.1/voice",
            " ws://127.0.0.1:8766/voice ",
        ).forEach { assertTrue(it, TermuxLaunchPolicy.isLoopbackUrl(it)) }
    }

    @Test
    fun rejectsNonLoopbackOrMalformedUrls() {
        listOf(
            "ws://192.168.1.10:8766/voice",
            "ws://127.0.0.1.evil.com/voice",
            "ws://localhost.example.com/voice",
            "ws://128.0.0.1/voice",
            "http://127.0.0.1:8766/voice",
            "ws://",
            "",
        ).forEach { assertFalse(it, TermuxLaunchPolicy.isLoopbackUrl(it)) }
    }

    @Test
    fun onlyUnmetPrerequisitesBlockCarMode() {
        assertTrue(TermuxLaunchPolicy.isBlocking(TermuxStatus.NOT_INSTALLED))
        assertTrue(TermuxLaunchPolicy.isBlocking(TermuxStatus.PERMISSION_MISSING))
        assertTrue(TermuxLaunchPolicy.isBlocking(TermuxStatus.INVALID_PATH))
        assertFalse(TermuxLaunchPolicy.isBlocking(TermuxStatus.DISABLED))
        assertFalse(TermuxLaunchPolicy.isBlocking(TermuxStatus.NOT_LOOPBACK))
        assertFalse(TermuxLaunchPolicy.isBlocking(TermuxStatus.READY))
    }

    @Test
    fun usesTheNamesDefinedByTermuxConstants() {
        assertEquals("com.termux.RUN_COMMAND", TermuxRunCommand.ACTION)
        assertEquals("com.termux.app.RunCommandService", TermuxRunCommand.SERVICE)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxRunCommand.PERMISSION)
        assertEquals("com.termux.RUN_COMMAND_PATH", TermuxRunCommand.EXTRA_COMMAND_PATH)
        assertEquals("com.termux.RUN_COMMAND_BACKGROUND", TermuxRunCommand.EXTRA_BACKGROUND)
    }
}
