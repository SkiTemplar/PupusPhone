package com.pupus.phone.ui

import com.pupus.phone.voice.VoiceState

enum class BubbleSize { SMALL, LARGE }

/**
 * Size of the floating push-to-talk bubble. In car mode it is large (easy to hit while driving),
 * except over a navigation app (it would cover the map) or while the assistant is showing
 * something it has just opened. Pure.
 */
object BubbleSizePolicy {
    val DEFAULT_NAVIGATION_PACKAGES: Set<String> = setOf("com.google.android.apps.maps", "com.waze")

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val SEPARATORS = Regex("[\\s,;]+")

    fun of(isCarMode: Boolean, foregroundPackage: String?, isShowing: Boolean, navigationPackages: Set<String>): BubbleSize =
        if (isCarMode && !isShowing && foregroundPackage !in navigationPackages) BubbleSize.LARGE else BubbleSize.SMALL

    /** Navigation package list typed in Settings: comma, semicolon or whitespace separated; invalid names dropped. */
    fun parsePackages(text: String): Set<String> =
        text.split(SEPARATORS).map { it.trim() }.filter { PACKAGE_NAME.matches(it) }.toSet()

    /**
     * Whether a TYPE_WINDOW_STATE_CHANGED package is an app in front: our own windows, System UI
     * (notification shade) and keyboards do not change what the user is looking at.
     */
    fun isForegroundApp(packageName: String?, ignored: Set<String>): Boolean =
        !packageName.isNullOrBlank() && packageName !in ignored
}

/**
 * Detects "the assistant opened something to show it": a turn that spoke (so it was not `silent`)
 * and during which a non-launcher app came to the front. The bubble then stays small until the
 * user goes back to the home screen or [HOLD_MS] pass. Derived from the voice states and the
 * foreground events the phone already sees, so it needs no bridge change. Immutable.
 */
data class ShowingTracker(
    val isTurnRunning: Boolean = false,
    val hasSpoken: Boolean = false,
    val openedPackage: String? = null,
    val showingPackage: String? = null,
    val showingSinceMs: Long = 0L,
) {
    fun onVoiceState(state: VoiceState, nowMs: Long): ShowingTracker = when (state) {
        VoiceState.STOPPED -> ShowingTracker()
        VoiceState.THINKING, VoiceState.SPEAKING -> onTurnActive(state == VoiceState.SPEAKING)
        VoiceState.IDLE, VoiceState.LISTENING -> if (isTurnRunning) onTurnEnded(nowMs) else this
    }

    fun onForeground(packageName: String, isLauncher: Boolean): ShowingTracker = when {
        isLauncher -> copy(openedPackage = null, showingPackage = null)
        isTurnRunning -> copy(openedPackage = packageName)
        else -> this
    }

    fun isShowing(nowMs: Long): Boolean = showingPackage != null && nowMs - showingSinceMs < HOLD_MS

    /** When [isShowing] turns false by itself, or null if it is not showing. */
    fun expiresAtMs(): Long? = if (showingPackage != null) showingSinceMs + HOLD_MS else null

    private fun onTurnActive(isSpeaking: Boolean): ShowingTracker =
        if (isTurnRunning) {
            copy(hasSpoken = hasSpoken || isSpeaking)
        } else {
            copy(isTurnRunning = true, hasSpoken = isSpeaking, openedPackage = null)
        }

    private fun onTurnEnded(nowMs: Long): ShowingTracker {
        val ended = copy(isTurnRunning = false, hasSpoken = false, openedPackage = null)
        val opened = openedPackage ?: return ended
        return if (hasSpoken) ended.copy(showingPackage = opened, showingSinceMs = nowMs) else ended
    }

    companion object {
        const val HOLD_MS = 5 * 60 * 1000L
    }
}

/** Where the bubble goes when its window changes size: same side edge, same centre height, on screen. */
object BubbleResize {
    fun place(x: Int, y: Int, fromSize: Int, toSize: Int, screenWidth: Int, screenHeight: Int, margin: Int): BubblePosition {
        val isRightAnchored = x + fromSize / 2 >= screenWidth / 2
        val rawX = if (isRightAnchored) x + fromSize - toSize else x
        val maxX = (screenWidth - toSize - margin).coerceAtLeast(margin)
        val centerY = y + fromSize / 2
        return BubblePosition(
            rawX.coerceIn(margin, maxX),
            OverlaySnap.clampY(centerY - toSize / 2, toSize, screenHeight, margin),
        )
    }
}
