package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTrackerTest {

    @Test
    fun abortReturnsTheIdToCancelOnlyOnce() {
        val turn = TurnTracker()
        turn.begin("t1")
        assertFalse(turn.isDone)
        assertEquals("t1", turn.abort())
        assertTrue(turn.isDone)
        assertNull(turn.currentId)
        assertNull(turn.abort())
    }

    @Test
    fun resetDuringTurnEndsItAndCancelsIt() {
        val turn = TurnTracker()
        turn.begin("t1")
        assertEquals("t1", turn.resetConversation())
        assertTrue(turn.isDone)
        assertFalse(turn.hasTurn)
    }

    @Test
    fun replyAppendsWithinTheSameTurn() {
        val turn = TurnTracker()
        val first = turn.appendReply("t1", "Hola.", "")
        assertEquals("Hola.", first)
        assertEquals("Hola. Vale.", turn.appendReply("t1", "Vale.", first))
        assertEquals("Otra.", turn.appendReply("t2", "Otra.", "Hola. Vale."))
    }

    @Test
    fun resetForgetsTheShownReplyTurn() {
        val turn = TurnTracker()
        turn.appendReply("t1", "Hola.", "")
        turn.resetConversation()
        assertEquals("Nuevo.", turn.appendReply("t1", "Nuevo.", "Hola."))
    }

    @Test
    fun onlyTheCurrentIdMatches() {
        val turn = TurnTracker()
        assertFalse(turn.isCurrent(null))
        turn.begin("t1")
        assertTrue(turn.isCurrent("t1"))
        assertFalse(turn.isCurrent("t0"))
        turn.markDone()
        assertTrue(turn.isDone)
        assertTrue(turn.isCurrent("t1"))
    }
}
