package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {

    @Test
    fun utteranceStartsATurnWithAnEmptyReply() {
        val history = ConversationHistory().appendUtterance("a", "  hola  ")
        assertEquals(listOf(ConversationTurn("a", "hola")), history.turns)
    }

    @Test
    fun sayFramesAreConcatenatedOnTheMatchingTurn() {
        val history = ConversationHistory()
            .appendUtterance("a", "uno")
            .appendUtterance("b", "dos")
            .appendSay("a", "Primera frase.")
            .appendSay("b", "Otra.")
            .appendSay("a", "Segunda frase.")
        assertEquals("Primera frase. Segunda frase.", history.turns[0].reply)
        assertEquals("Otra.", history.turns[1].reply)
    }

    @Test
    fun framesForAnUnknownTurnAreIgnored() {
        val history = ConversationHistory().appendUtterance("a", "uno")
        assertSame(history, history.appendSay("x", "perdido"))
        assertSame(history, history.appendStatus("x", "perdido"))
    }

    @Test
    fun blankFramesDoNotChangeTheHistory() {
        val history = ConversationHistory().appendUtterance("a", "uno")
        assertSame(history, history.appendSay("a", "   "))
        assertSame(history, history.appendStatus("a", ""))
    }

    @Test
    fun statusLinesSkipConsecutiveDuplicates() {
        val history = ConversationHistory()
            .appendUtterance("a", "uno")
            .appendStatus("a", "Abriendo Maps")
            .appendStatus("a", "Abriendo Maps")
            .appendStatus("a", "Buscando")
        assertEquals(listOf("Abriendo Maps", "Buscando"), history.turns[0].statuses)
    }

    @Test
    fun operationsDoNotMutateThePreviousInstance() {
        val before = ConversationHistory().appendUtterance("a", "uno")
        before.appendSay("a", "respuesta")
        before.appendUtterance("b", "dos")
        assertEquals(listOf(ConversationTurn("a", "uno")), before.turns)
    }

    @Test
    fun capDropsTheOldestTurns() {
        var history = ConversationHistory(maxTurns = 3)
        for (i in 1..5) history = history.appendUtterance("t$i", "frase $i")
        assertEquals(listOf("t3", "t4", "t5"), history.turns.map { it.id })
    }

    @Test
    fun repeatedUtteranceIdReplacesTheOldTurn() {
        val history = ConversationHistory()
            .appendUtterance("a", "uno")
            .appendSay("a", "vieja")
            .appendUtterance("a", "otra vez")
        assertEquals(listOf(ConversationTurn("a", "otra vez")), history.turns)
    }

    @Test
    fun resetEmptiesAndKeepsTheCap() {
        val history = ConversationHistory(maxTurns = 7).appendUtterance("a", "uno").reset()
        assertTrue(history.isEmpty)
        assertEquals(7, history.maxTurns)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveCapIsRejected() {
        ConversationHistory(maxTurns = 0)
    }
}
