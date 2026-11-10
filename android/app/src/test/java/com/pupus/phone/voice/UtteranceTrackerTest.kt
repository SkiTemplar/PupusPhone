package com.pupus.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceTrackerTest {

    @Test
    fun drainsWhenLastUtteranceOfTurnFinishes() {
        val tracker = UtteranceTracker()
        val a = tracker.next()
        val b = tracker.next()
        assertFalse(tracker.finish(a))
        assertTrue(tracker.finish(b))
        assertFalse(tracker.isBusy)
    }

    @Test
    fun lateDoneFromCancelledTurnIsIgnored() {
        val tracker = UtteranceTracker()
        val old = tracker.next()
        tracker.newTurn()
        val current = tracker.next()
        assertFalse(tracker.finish(old))
        assertTrue(tracker.isBusy)
        assertTrue(tracker.finish(current))
    }

    @Test
    fun unknownOrDuplicateIdsAreIgnored() {
        val tracker = UtteranceTracker()
        val id = tracker.next()
        assertFalse(tracker.finish(null))
        assertFalse(tracker.finish("t9-u9"))
        assertTrue(tracker.finish(id))
        assertFalse(tracker.finish(id))
    }
}
