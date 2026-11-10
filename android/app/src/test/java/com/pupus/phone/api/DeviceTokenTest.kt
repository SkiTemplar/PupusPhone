package com.pupus.phone.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTokenTest {
    private val token = "a".repeat(64)

    @Test
    fun generatesSixtyFourHexChars() {
        val generated = DeviceToken.generate()
        assertEquals(64, generated.length)
        assertTrue(DeviceToken.isValidFormat(generated))
        assertNotEquals(generated, DeviceToken.generate())
    }

    @Test
    fun acceptsExactBearer() {
        assertTrue(DeviceToken.isAuthorized("Bearer $token", token))
    }

    @Test
    fun rejectsMissingWrongOrMalformed() {
        assertFalse(DeviceToken.isAuthorized(null, token))
        assertFalse(DeviceToken.isAuthorized("", token))
        assertFalse(DeviceToken.isAuthorized(token, token))
        assertFalse(DeviceToken.isAuthorized("Basic $token", token))
        assertFalse(DeviceToken.isAuthorized("Bearer ${"b".repeat(64)}", token))
        assertFalse(DeviceToken.isAuthorized("Bearer ${token.dropLast(1)}", token))
        assertFalse(DeviceToken.isAuthorized("Bearer ", ""))
    }

    @Test
    fun formatCheckRejectsUppercaseAndWrongLength() {
        assertFalse(DeviceToken.isValidFormat("A".repeat(64)))
        assertFalse(DeviceToken.isValidFormat("a".repeat(63)))
    }
}
