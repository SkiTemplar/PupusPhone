package com.pupus.phone.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerChoiceTest {

    @Test
    fun autoPrefersOnDeviceWhenSpanishIsInstalled() {
        assertEquals(RecognizerKind.ON_DEVICE, RecognizerChoice.pick(RecognizerMode.AUTO, true, true, false))
    }

    @Test
    fun autoUsesDefaultWhenSpanishSupportIsUnknownOrMissing() {
        assertEquals(RecognizerKind.DEFAULT, RecognizerChoice.pick(RecognizerMode.AUTO, true, null, false))
        assertEquals(RecognizerKind.DEFAULT, RecognizerChoice.pick(RecognizerMode.AUTO, true, false, false))
        assertEquals(RecognizerKind.DEFAULT, RecognizerChoice.pick(RecognizerMode.AUTO, false, true, false))
    }

    @Test
    fun autoUsesDefaultAfterOnDeviceFailedWithLanguageError() {
        assertEquals(RecognizerKind.DEFAULT, RecognizerChoice.pick(RecognizerMode.AUTO, true, true, true))
    }

    @Test
    fun forcedModesAreHonouredWhenPossible() {
        assertEquals(RecognizerKind.ON_DEVICE, RecognizerChoice.pick(RecognizerMode.ON_DEVICE, true, false, true))
        assertEquals(RecognizerKind.DEFAULT, RecognizerChoice.pick(RecognizerMode.ON_DEVICE, false, null, false))
        assertEquals(RecognizerKind.DEFAULT, RecognizerChoice.pick(RecognizerMode.DEFAULT, true, true, false))
    }

    @Test
    fun fallsBackOnlyFromOnDeviceInAutoOnLanguageErrors() {
        val lang = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        assertTrue(RecognizerChoice.shouldFallBack(lang, RecognizerMode.AUTO, RecognizerKind.ON_DEVICE))
        assertTrue(
            RecognizerChoice.shouldFallBack(
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, RecognizerMode.AUTO, RecognizerKind.ON_DEVICE,
            ),
        )
        assertFalse(RecognizerChoice.shouldFallBack(lang, RecognizerMode.ON_DEVICE, RecognizerKind.ON_DEVICE))
        assertFalse(RecognizerChoice.shouldFallBack(lang, RecognizerMode.AUTO, RecognizerKind.DEFAULT))
        assertFalse(
            RecognizerChoice.shouldFallBack(SpeechRecognizer.ERROR_NO_MATCH, RecognizerMode.AUTO, RecognizerKind.ON_DEVICE),
        )
    }

    @Test
    fun modeParsingFallsBackToAuto() {
        assertEquals(RecognizerMode.ON_DEVICE, RecognizerMode.parse("ON_DEVICE"))
        assertEquals(RecognizerMode.AUTO, RecognizerMode.parse("garbage"))
        assertEquals(RecognizerMode.AUTO, RecognizerMode.parse(null))
    }
}
