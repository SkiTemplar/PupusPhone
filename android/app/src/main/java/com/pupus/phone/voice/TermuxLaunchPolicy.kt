package com.pupus.phone.voice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Termux RUN_COMMAND API. Names copied from termux-app `TermuxConstants`
 * (TERMUX_APP.RUN_COMMAND_SERVICE and PERMISSION_RUN_COMMAND).
 */
object TermuxRunCommand {
    const val PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    // A path inside Termux's private data, read by Termux itself: not our own files dir.
    @Suppress("SdCardPath")
    const val DEFAULT_SCRIPT_PATH ="/data/data/com.termux/files/home/PupusPhone/scripts/termux-start.sh"
}

/** Why the bridge would or would not be started in Termux. Only [READY] sends the intent. */
enum class TermuxStatus { DISABLED, NOT_LOOPBACK, NOT_INSTALLED, PERMISSION_MISSING, INVALID_PATH, READY }

/** Snapshot of everything the decision depends on; built from settings and the package manager. */
data class TermuxEnvironment(
    val isEnabled: Boolean,
    val bridgeUrl: String,
    val scriptPath: String,
    val isTermuxInstalled: Boolean,
    val isPermissionGranted: Boolean,
)

/** Decides whether car mode must ask Termux to start the voice bridge. Pure: no Android calls. */
object TermuxLaunchPolicy {
    private const val LOCALHOST = "localhost"
    private const val IPV6_LOOPBACK = "::1"
    private val IPV4_LOOPBACK = Regex("""^127(\.\d{1,3}){3}$""")

    fun status(env: TermuxEnvironment): TermuxStatus = when {
        !env.isEnabled -> TermuxStatus.DISABLED
        !isLoopbackUrl(env.bridgeUrl) -> TermuxStatus.NOT_LOOPBACK
        !env.isTermuxInstalled -> TermuxStatus.NOT_INSTALLED
        !env.isPermissionGranted -> TermuxStatus.PERMISSION_MISSING
        !isValidScriptPath(env.scriptPath) -> TermuxStatus.INVALID_PATH
        else -> TermuxStatus.READY
    }

    fun shouldLaunch(env: TermuxEnvironment): Boolean = status(env) == TermuxStatus.READY

    /**
     * True when the user expects the bridge on this phone but it cannot be started:
     * car mode must say why instead of silently retrying the connection.
     */
    fun isBlocking(status: TermuxStatus): Boolean = when (status) {
        TermuxStatus.NOT_INSTALLED, TermuxStatus.PERMISSION_MISSING, TermuxStatus.INVALID_PATH -> true
        TermuxStatus.DISABLED, TermuxStatus.NOT_LOOPBACK, TermuxStatus.READY -> false
    }

    /** ws(s) URL whose host is 127.0.0.0/8, localhost or ::1. */
    fun isLoopbackUrl(url: String): Boolean {
        val host = hostOf(url.trim())?.lowercase() ?: return false
        return host == LOCALHOST || host == IPV6_LOOPBACK || isIpv4Loopback(host)
    }

    /** Termux expands `~/` to its home; anything else must be an absolute path. */
    fun isValidScriptPath(path: String): Boolean {
        val trimmed = path.trim()
        return (trimmed.startsWith("/") && trimmed.length > 1) || (trimmed.startsWith("~/") && trimmed.length > 2)
    }

    private fun isIpv4Loopback(host: String): Boolean =
        IPV4_LOOPBACK.matches(host) && host.split('.').all { it.toInt() <= MAX_OCTET }

    private fun hostOf(url: String): String? {
        val http = when {
            url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(WS_PREFIX)
            url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(WSS_PREFIX)
            else -> return null
        }
        return http.toHttpUrlOrNull()?.host
    }

    private const val WS_PREFIX = 5
    private const val WSS_PREFIX = 6
    private const val MAX_OCTET = 255
}
