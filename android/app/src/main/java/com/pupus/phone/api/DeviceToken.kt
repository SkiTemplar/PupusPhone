package com.pupus.phone.api

import java.security.MessageDigest
import java.security.SecureRandom

/** Persistence for the device token. [write] returns false when it could not be stored. */
interface TokenStore {
    fun read(): String?
    fun write(token: String): Boolean
}

/** Device API token: 32 random bytes in lowercase hex, compared in constant time. */
object DeviceToken {
    private const val TOKEN_BYTES = 32
    private const val BEARER_PREFIX = "Bearer "
    private const val MASK_VISIBLE = 4
    private const val ELLIPSIS = "…"
    private const val MAX_ROTATE_ATTEMPTS = 4

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

    /** The stored token, or a new one (stored) when missing or malformed. */
    fun getOrCreate(store: TokenStore, generator: () -> String = { generate() }): Persisted {
        val existing = store.read()
        if (existing != null && isValidFormat(existing)) return Persisted(existing, isStored = true)
        val created = generator()
        return Persisted(created, store.write(created))
    }

    /** Replaces the stored token with a fresh one that differs from the current token. */
    fun rotate(store: TokenStore, generator: () -> String = { generate() }): Persisted {
        val current = store.read()
        val created = generateSequence(generator).take(MAX_ROTATE_ATTEMPTS)
            .firstOrNull { it != current && isValidFormat(it) }
            ?: throw IllegalStateException("Token generator returned no new valid token")
        return Persisted(created, store.write(created))
    }

    /** `abcd…wxyz` for display; tokens too short to mask reveal nothing. */
    fun mask(token: String): String =
        if (token.length <= MASK_VISIBLE * 2) ELLIPSIS
        else token.take(MASK_VISIBLE) + ELLIPSIS + token.takeLast(MASK_VISIBLE)

    /** [token] is in effect for this process; [isStored] is false when it was not persisted. */
    data class Persisted(val token: String, val isStored: Boolean)
}
