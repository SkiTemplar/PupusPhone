package com.pupus.phone.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionTest {
    private var now = 0L
    private val config = DictationConfig(silenceMs = 2_500, noSpeechMs = 8_000, maxMs = 120_000, maxRecreates = 2)

    private fun session(): DictationSession = DictationSession(config) { now }.also { it.start() }

    @Test
    fun segmentsAreConcatenatedWithSpaces() {
        val s = session()
        s.onPartial("hola")
        assertEquals(DictationStep.Restart, s.onFinal("  hola  "))
        s.onPartial("qué tal")
        assertEquals(DictationStep.Restart, s.onFinal("qué tal estás"))
        assertEquals("hola qué tal estás", s.transcript)
    }

    @Test
    fun finalRepeatingLastPartialIsNotDuplicated() {
        val s = session()
        s.onPartial("dile a Ana")
        s.onFinal("dile a Ana")
        assertEquals("dile a Ana", s.transcript)
    }

    @Test
    fun duplicateFinalWithoutNewSpeechIsDropped() {
        val s = session()
        s.onPartial("vale")
        s.onFinal("vale")
        s.onFinal("vale")
        assertEquals("vale", s.transcript)
    }

    @Test
    fun blankFinalCommitsPendingPartial() {
        val s = session()
        s.onPartial("llego en diez minutos")
        s.onFinal("")
        s.onPartial("más o menos")
        assertEquals("llego en diez minutos más o menos", s.transcript)
    }

    @Test
    fun repeatedPartialDoesNotResetSilenceTimer() {
        val s = session()
        assertTrue(s.onPartial("hola"))
        now = 1_000
        assertFalse(s.onPartial("hola"))
        now = 3_000
        assertEquals(DictationStep.Send("hola"), s.onTick())
    }

    @Test
    fun sendsOnSilenceDeadline() {
        val s = session()
        now = 500
        s.onPartial("pon música")
        now = 2_999
        assertEquals(DictationStep.Wait, s.onTick())
        now = 3_000
        assertEquals(DictationStep.Send("pon música"), s.onTick())
        assertTrue(s.isFinished)
    }

    @Test
    fun newWordsPostponeTheSilenceDeadline() {
        val s = session()
        s.onPartial("escribe a Juan")
        now = 2_000
        s.onPartial("escribe a Juan que")
        now = 4_000
        assertEquals(DictationStep.Wait, s.onTick())
        now = 4_500
        assertEquals(DictationStep.Send("escribe a Juan que"), s.onTick())
    }

    @Test
    fun sendsOnSecondTap() {
        val s = session()
        s.onPartial("abre maps")
        now = 300
        assertEquals(DictationStep.Send("abre maps"), s.onTap())
        assertEquals(DictationStep.Wait, s.onTick())
    }

    @Test
    fun tapWithNothingHeardSendsNothing() {
        val s = session()
        assertEquals(DictationStep.NothingHeard, s.onTap())
    }

    @Test
    fun noSpeechTimeoutSendsNothing() {
        val s = session()
        now = 7_999
        assertEquals(DictationStep.Wait, s.onTick())
        assertEquals(DictationStep.Restart, s.onError(SpeechRecognizer.ERROR_NO_MATCH))
        now = 8_000
        assertEquals(DictationStep.NothingHeard, s.onTick())
    }

    @Test
    fun noSpeechTimeoutAlsoAppliesWhenSegmentEnds() {
        val s = session()
        now = 8_100
        assertEquals(DictationStep.NothingHeard, s.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun hardCapSendsAccumulatedText() {
        val s = session()
        var t = 0L
        while (t < 119_000) {
            now = t
            s.onPartial("palabra $t")
            assertEquals(DictationStep.Wait, s.onTick())
            t += 1_000
        }
        now = 120_000
        s.onPartial("última")
        val step = s.onTick()
        assertTrue(step is DictationStep.Send)
        assertTrue((step as DictationStep.Send).text.endsWith("última"))
    }

    @Test
    fun finalBeforeDeadlineRestartsAndAfterDeadlineSends() {
        val s = session()
        s.onPartial("uno")
        assertEquals(DictationStep.Restart, s.onFinal("uno"))
        now = 2_600
        assertEquals(DictationStep.Send("uno"), s.onError(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun noMatchAndSpeechTimeoutRestart() {
        val s = session()
        assertEquals(DictationStep.Restart, s.onError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(DictationStep.Restart, s.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun busyAndClientRecreateUntilLimit() {
        val s = session()
        assertEquals(DictationStep.Recreate, s.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(DictationStep.Recreate, s.onError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(DictationStep.Fail(SpeechRecognizer.ERROR_CLIENT), s.onError(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun speechResetsRecreateStreak() {
        val s = session()
        s.onError(SpeechRecognizer.ERROR_CLIENT)
        s.onError(SpeechRecognizer.ERROR_CLIENT)
        s.onPartial("sigo aquí")
        assertEquals(DictationStep.Recreate, s.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test
    fun cleanSegmentEndResetsRecreateStreak() {
        val s = session()
        repeat(5) {
            assertEquals(DictationStep.Recreate, s.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
            assertEquals(DictationStep.Restart, s.onError(SpeechRecognizer.ERROR_NO_MATCH))
        }
    }

    @Test
    fun fatalErrorFailsWhenNothingHeard() {
        val s = session()
        assertEquals(DictationStep.Fail(SpeechRecognizer.ERROR_AUDIO), s.onError(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test
    fun fatalErrorSendsWhatWasHeard() {
        val s = session()
        s.onPartial("llama a mamá")
        assertEquals(DictationStep.Send("llama a mamá"), s.onError(SpeechRecognizer.ERROR_NETWORK))
    }

    @Test
    fun eventsAfterFinishAreIgnored() {
        val s = session()
        s.onPartial("hola")
        s.onTap()
        assertFalse(s.onPartial("adiós"))
        assertEquals(DictationStep.Wait, s.onFinal("adiós"))
        assertEquals(DictationStep.Wait, s.onError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals("hola", s.transcript)
    }

    @Test
    fun silenceSettingIsClampedToRange() {
        assertEquals(1_000L, DictationConfig.clampSilence(10))
        assertEquals(6_000L, DictationConfig.clampSilence(60_000))
        assertEquals(2_500L, DictationConfig.clampSilence(2_500))
    }
}
