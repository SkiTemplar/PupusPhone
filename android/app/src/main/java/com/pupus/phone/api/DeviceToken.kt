package com.pupus.phone.api

import java.security.MessageDigest
import java.security.SecureRandom

/** Device API token: 32 random bytes in lowercase hex, compared in constant time. */
object DeviceToken {
    private const val TOKEN_BYTES = 32
    private const val BEARER_PREFIX = "Bearer "

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isValidFormat(token: String): Boolean =
        token.length == TOKEN_BYTES * 2 && token.all { it in '0'..'9' || it in 'a'..'f' }

    /** True when [authorizationHeader] is exactly `Bearer <expected>`. */
    fun isAuthorized(authorizationHeader: String?, expected: String): Boolean {
        if (authorizationHeader == null || expected.isEmpty()) return false
        if (!authorizationHeader.startsWith(BEARER_PREFIX)) return false
        val presented = authorizationHeader.substring(BEARER_PREFIX.length).trim()
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}
