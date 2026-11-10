package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {
    private val firstCount = ToneSynth.SAMPLE_RATE * ToneSynth.FIRST_NOTE_MS / 1000

    @Test
    fun cuesHaveTheDeclaredLength() {
        val expected = firstCount + ToneSynth.SAMPLE_RATE * ToneSynth.SECOND_NOTE_MS / 1000
        assertEquals(expected, ToneSynth.start().size)
        assertEquals(expected, ToneSynth.end().size)
    }

    @Test
    fun startRisesAndEndFalls() {
        val start = ToneSynth.start()
        val end = ToneSynth.end()
        assertTrue(rate(start, 0, firstCount) < rate(start, firstCount, start.size))
        assertTrue(rate(end, 0, firstCount) > rate(end, firstCount, end.size))
    }

    @Test
    fun notesStartAndEndSilentAndStayBelowPeak() {
        val start = ToneSynth.start()
        val limit = ToneSynth.PEAK * Short.MAX_VALUE + 1
        assertTrue(start.all { abs(it.toInt()) <= limit })
        assertEquals(0, start.first().toInt())
        assertTrue(abs(start[firstCount - 1].toInt()) < 0.01 * Short.MAX_VALUE)
        assertTrue(abs(start.last().toInt()) < 0.01 * Short.MAX_VALUE)
    }

    /** Zero crossings per sample: proportional to the fundamental frequency. */
    private fun rate(pcm: ShortArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
        return crossings.toDouble() / (to - from)
    }
}
