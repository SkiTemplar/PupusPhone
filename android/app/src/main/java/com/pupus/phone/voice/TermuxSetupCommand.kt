package com.pupus.phone.voice

import java.security.SecureRandom

/**
 * "Configurar Termux": the one command the user pastes into Termux to install and configure the
 * phone-only bridge. Pure so it can be unit-tested; tokens are validated because they end up in a
 * shell command line.
 */
object TermuxSetupCommand {
    const val SCRIPT_URL = "https://raw.githubusercontent.com/SkiTemplar/PupusPhone/main/scripts/termux-setup.sh"
    const val LOOPBACK_BRIDGE_URL = "ws://127.0.0.1:8766/voice"
    private const val BRIDGE_TOKEN_BYTES = 24
    private const val MIN_BRIDGE_TOKEN_LENGTH = 16
    private val DEVICE_TOKEN = Regex("[0-9a-f]{64}")
    private val SHELL_SAFE_TOKEN = Regex("[A-Za-z0-9._-]+")

    /** Bridge tokens that the bridge accepts (≥ 16 chars) and that need no shell quoting. */
    fun isUsableBridgeToken(token: String): Boolean =
        token.length >= MIN_BRIDGE_TOKEN_LENGTH && SHELL_SAFE_TOKEN.matches(token)

    /** [current] when usable, otherwise a fresh 48-char hex token. */
    fun ensureBridgeToken(current: String, generator: () -> String = { generateBridgeToken() }): String {
        val trimmed = current.trim()
        if (isUsableBridgeToken(trimmed)) return trimmed
        val created = generator()
        require(isUsableBridgeToken(created)) { "Generated bridge token is not usable" }
        return created
    }

    fun generateBridgeToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BRIDGE_TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** `curl … | bash -s -- --device-token … --bridge-token …`; rejects tokens that are not shell-safe. */
    fun build(deviceToken: String, bridgeToken: String, scriptUrl: String = SCRIPT_URL): String {
        require(DEVICE_TOKEN.matches(deviceToken)) { "Device token must be 64 lowercase hex characters" }
        require(isUsableBridgeToken(bridgeToken)) { "Bridge token must be at least 16 shell-safe characters" }
        require(scriptUrl.startsWith("https://") && scriptUrl.none { it.isWhitespace() || it == '\'' }) {
            "Script URL must be a plain https URL"
        }
        return "curl -fsSL '$scriptUrl' | bash -s -- --device-token $deviceToken --bridge-token $bridgeToken"
    }
}
