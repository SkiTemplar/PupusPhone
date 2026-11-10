package com.pupus.phone.ui

import kotlin.math.hypot

/** Top-left position of the floating bubble window, in screen pixels. */
data class BubblePosition(val x: Int, val y: Int)

/** Where the bubble is drawn during a drag and whether a release there dismisses it. */
data class DragPlacement(val position: BubblePosition, val isOverTarget: Boolean)

/**
 * Chat-heads dismiss target: a circle near the bottom centre of the screen. While the
 * bubble's centre is within [snapRadius] of the target's centre, the bubble is pulled
 * onto the target (magnetic snap) and releasing it there dismisses it.
 */
class DismissZone(
    val centerX: Int,
    val centerY: Int,
    private val snapRadius: Int,
) {
    init {
        require(snapRadius > 0) { "snapRadius must be positive" }
    }

    /** [raw] is where the finger drag would put the bubble; [bubbleSize] is its square side. */
    fun place(raw: BubblePosition, bubbleSize: Int): DragPlacement {
        val bubbleCenterX = raw.x + bubbleSize / 2
        val bubbleCenterY = raw.y + bubbleSize / 2
        val distance = hypot((bubbleCenterX - centerX).toDouble(), (bubbleCenterY - centerY).toDouble())
        if (distance > snapRadius) return DragPlacement(raw, isOverTarget = false)
        val snapped = BubblePosition(centerX - bubbleSize / 2, centerY - bubbleSize / 2)
        return DragPlacement(snapped, isOverTarget = true)
    }

    companion object {
        /** Target centred horizontally, [bottomOffset] px above the bottom edge of the screen. */
        fun forScreen(screenWidth: Int, screenHeight: Int, bottomOffset: Int, snapRadius: Int): DismissZone =
            DismissZone(screenWidth / 2, (screenHeight - bottomOffset).coerceAtLeast(0), snapRadius)
    }
}
