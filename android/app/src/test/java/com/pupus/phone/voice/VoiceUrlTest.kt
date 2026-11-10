package com.pupus.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUrlTest {

    @Test
    fun acceptsWellFormedWebSocketUrls() {
        assertTrue(VoiceUrl.isValidBase("ws://100.64.0.1:8766/voice"))
        assertTrue(VoiceUrl.isValidBase(" wss://bridge.example.com/voice "))
        assertTrue(VoiceUrl.isValidBase("ws://host"))
    }

    @Test
    fun rejectsNonNumericOrOutOfRangePort() {
        assertFalse(VoiceUrl.isValidBase("ws://host:abc"))
        assertFalse(VoiceUrl.isValidBase("ws://host:0/voice"))
        assertFalse(VoiceUrl.isValidBase("ws://host:70000/voice"))
        assertNull(VoiceUrl.build("ws://host:abc", "t"))
    }

    @Test
    fun rejectsMissingHostOrWrongScheme() {
        assertFalse(VoiceUrl.isValidBase("ws://"))
        assertFalse(VoiceUrl.isValidBase("ws://:8766/voice"))
        assertFalse(VoiceUrl.isValidBase("http://host:8766/voice"))
        assertFalse(VoiceUrl.isValidBase("host:8766"))
        assertFalse(VoiceUrl.isValidBase(""))
    }
}
