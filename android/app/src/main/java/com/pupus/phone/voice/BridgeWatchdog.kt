package com.pupus.phone.voice

/** Result of one watchdog tick: the next state and whether Termux must be asked to start the bridge. */
data class WatchdogStep(val state: BridgeWatchdog, val shouldRelaunch: Boolean)

/**
 * Car mode with a loopback bridge: if the WebSocket has been down for more than [graceMs]
 * (Termux killed, e.g. swiped away on HyperOS), re-send the idempotent Termux start,
 * at most once per [minIntervalMs]. Immutable and pure: the service keeps the latest state.
 */
data class BridgeWatchdog(
    val downSinceMs: Long? = null,
    val lastLaunchMs: Long? = null,
    val graceMs: Long = GRACE_MS,
    val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    /** The car-mode start already sent a launch: the interval counts from it. */
    fun onLaunched(nowMs: Long): BridgeWatchdog = copy(lastLaunchMs = nowMs)

    fun tick(nowMs: Long, isConnected: Boolean, isLoopback: Boolean): WatchdogStep {
        if (isConnected || !isLoopback) return WatchdogStep(copy(downSinceMs = null), shouldRelaunch = false)
        val since = downSinceMs ?: nowMs
        val isDownLongEnough = nowMs - since > graceMs
        val isIntervalOver = lastLaunchMs?.let { nowMs - it >= minIntervalMs } ?: true
        if (!isDownLongEnough || !isIntervalOver) return WatchdogStep(copy(downSinceMs = since), shouldRelaunch = false)
        return WatchdogStep(copy(downSinceMs = since, lastLaunchMs = nowMs), shouldRelaunch = true)
    }

    companion object {
        const val GRACE_MS = 15_000L
        const val MIN_INTERVAL_MS = 60_000L
        const val TICK_MS = 5_000L
    }
}
