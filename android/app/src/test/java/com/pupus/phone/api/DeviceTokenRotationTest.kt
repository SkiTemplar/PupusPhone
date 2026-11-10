package com.pupus.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryTokenStore(var value: String? = null, private val canWrite: Boolean = true) : TokenStore {
    var writes = 0
    override fun read(): String? = value
    override fun write(token: String): Boolean {
        writes++
        value = token
        return canWrite
    }
}

private class OkController : DeviceController {
    override fun health() = Outcome.ok(true)
    override fun tree(visibleOnly: Boolean) = Outcome.ok(true)
    override fun screenshot(params: ShotParams) = Outcome.ok(true)
    override fun tap(target: TapTarget) = Outcome.ok(true)
    override fun longPress(request: LongPressRequest) = Outcome.ok(true)
    override fun swipe(request: SwipeRequest) = Outcome.ok(true)
    override fun scroll(request: ScrollRequest) = Outcome.ok(true)
    override fun text(request: TextRequest) = Outcome.ok(true)
    override fun global(action: GlobalActionName) = Outcome.ok(true)
    override fun apps() = Outcome.ok(true)
    override fun launch(pkg: String) = Outcome.ok(true)
    override fun intent(request: IntentRequest) = Outcome.ok(true)
    override fun media(action: MediaAction) = Outcome.ok(true)
}

class DeviceTokenRotationTest {
    private val old = "a".repeat(64)
    private val new = "b".repeat(64)

    @Test
    fun getOrCreateKeepsAValidStoredToken() {
        val store = InMemoryTokenStore(old)
        assertEquals(DeviceToken.Persisted(old, isStored = true), DeviceToken.getOrCreate(store) { new })
        assertEquals(0, store.writes)
    }

    @Test
    fun getOrCreateReplacesMissingOrMalformed() {
        val store = InMemoryTokenStore("not-a-token")
        assertEquals(DeviceToken.Persisted(new, isStored = true), DeviceToken.getOrCreate(store) { new })
        assertEquals(new, store.value)
    }

    @Test
    fun rotateStoresAFreshValidToken() {
        val store = InMemoryTokenStore(old)
        val result = DeviceToken.rotate(store)
        assertTrue(result.isStored)
        assertTrue(DeviceToken.isValidFormat(result.token))
        assertNotEquals(old, result.token)
        assertEquals(result.token, store.value)
    }

    @Test
    fun rotateNeverReturnsTheCurrentToken() {
        val outputs = ArrayDeque(listOf(old, "XYZ", new))
        val result = DeviceToken.rotate(InMemoryTokenStore(old)) { outputs.removeFirst() }
        assertEquals(new, result.token)
    }

    @Test(expected = IllegalStateException::class)
    fun rotateGivesUpOnABrokenGenerator() {
        DeviceToken.rotate(InMemoryTokenStore(old)) { old }
    }

    @Test
    fun rotateReportsAFailedCommit() {
        val result = DeviceToken.rotate(InMemoryTokenStore(old, canWrite = false)) { new }
        assertFalse(result.isStored)
        assertEquals(new, result.token)
    }

    @Test
    fun runningApiAcceptsOnlyTheRotatedTokenWithoutRestart() {
        val store = InMemoryTokenStore(old)
        val handler = RouteHandler({ DeviceToken.getOrCreate(store).token }, OkController())
        assertTrue(handler.isAuthorized("Bearer $old"))
        val rotated = DeviceToken.rotate(store).token
        assertFalse(handler.isAuthorized("Bearer $old"))
        assertTrue(handler.isAuthorized("Bearer $rotated"))
    }

    @Test
    fun maskShowsOnlyFourCharsAtEachEnd() {
        val token = "0123" + "f".repeat(56) + "wxyz"
        assertEquals("0123…wxyz", DeviceToken.mask(token))
        assertEquals("…", DeviceToken.mask("12345678"))
        assertEquals("…", DeviceToken.mask(""))
    }
}
