package com.pupus.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DismissZoneTest {
    // 1080x2400 screen, target centre 300 px above the bottom, 250 px snap radius, 240 px bubble.
    private val zone = DismissZone.forScreen(screenWidth = 1080, screenHeight = 2400, bottomOffset = 300, snapRadius = 250)
    private val size = 240

    @Test
    fun targetSitsAtBottomCentre() {
        assertEquals(540, zone.centerX)
        assertEquals(2100, zone.centerY)
    }

    @Test
    fun farFromTargetFollowsTheFinger() {
        val raw = BubblePosition(100, 600)
        val placement = zone.place(raw, size)
        assertFalse(placement.isOverTarget)
        assertEquals(raw, placement.position)
    }

    @Test
    fun withinRadiusSnapsBubbleCentreOntoTarget() {
        // Bubble centre at (640, 2000): about 141 px from the target centre.
        val placement = zone.place(BubblePosition(520, 1880), size)
        assertTrue(placement.isOverTarget)
        assertEquals(BubblePosition(540 - 120, 2100 - 120), placement.position)
    }

    @Test
    fun radiusBoundaryIsInclusive() {
        // Bubble centre exactly 250 px to the left of the target centre.
        assertTrue(zone.place(BubblePosition(290 - 120, 2100 - 120), size).isOverTarget)
        assertFalse(zone.place(BubblePosition(289 - 120, 2100 - 120), size).isOverTarget)
    }

    @Test
    fun usesCentresNotTopLeftCorners() {
        // Top-left corner on the target centre, but the bubble centre is 170 px away diagonally.
        assertTrue(zone.place(BubblePosition(540, 2100), size).isOverTarget)
        // Corner within reach but the centre is 305 px away: no snap.
        assertFalse(zone.place(BubblePosition(540 + 95, 2100 + 95), size).isOverTarget)
    }

    @Test
    fun targetStaysOnScreenWhenOffsetExceedsHeight() {
        assertEquals(0, DismissZone.forScreen(100, 200, bottomOffset = 500, snapRadius = 10).centerY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveRadius() {
        DismissZone(0, 0, snapRadius = 0)
    }
}
