package com.pupus.phone.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessagesTest {

    @Test
    fun encodesOutgoingFrames() {
        val hello = JSONObject(VoiceMessages.hello("0.1.0"))
        assertEquals("hello", hello.getString("type"))
        assertEquals("android", hello.getString("client"))
        assertEquals("0.1.0", hello.getString("version"))

        val utterance = JSONObject(VoiceMessages.utterance("t1", "pon \"Queen\""))
        assertEquals("utterance", utterance.getString("type"))
        assertEquals("t1", utterance.getString("id"))
        assertEquals("pon \"Queen\"", utterance.getString("text"))

        val cancel = JSONObject(VoiceMessages.cancel("t1"))
        assertEquals("cancel", cancel.getString("type"))
        assertEquals("t1", cancel.getString("id"))

        val reset = JSONObject(VoiceMessages.reset())
        assertEquals("reset", reset.getString("type"))
        assertEquals(1, reset.length())
    }

    @Test
    fun decodesIncomingFrames() {
        assertEquals(BridgeMessage.Ready("sonnet"), VoiceMessages.decode("""{"type":"ready","model":"sonnet"}"""))
        assertEquals(BridgeMessage.Say("t1", "Hola."), VoiceMessages.decode("""{"type":"say","id":"t1","text":"Hola."}"""))
        assertEquals(
            BridgeMessage.Status("t1", "Abriendo Spotify…"),
            VoiceMessages.decode("""{"type":"status","id":"t1","text":"Abriendo Spotify…"}"""),
        )
        assertEquals(BridgeMessage.Done("t1", 1234), VoiceMessages.decode("""{"type":"done","id":"t1","durationMs":1234}"""))
        assertEquals(BridgeMessage.Error("t1", "fallo"), VoiceMessages.decode("""{"type":"error","id":"t1","message":"fallo"}"""))
        val noId = VoiceMessages.decode("""{"type":"error","message":"sin turno"}""") as BridgeMessage.Error
        assertNull(noId.id)
    }

    @Test
    fun decodesSilentDone() {
        assertEquals(
            BridgeMessage.Done("t1", 80, isSilent = true),
            VoiceMessages.decode("""{"type":"done","id":"t1","durationMs":80,"silent":true}"""),
        )
        val spoken = VoiceMessages.decode("""{"type":"done","id":"t1","durationMs":80}""") as BridgeMessage.Done
        assertEquals(false, spoken.isSilent)
        val notBoolean = VoiceMessages.decode("""{"type":"done","id":"t1","silent":"true"}""") as BridgeMessage.Done
        assertEquals(false, notBoolean.isSilent)
    }

    @Test
    fun rejectsInvalidFrames() {
        assertTrue(VoiceMessages.decode("not json") is BridgeMessage.Invalid)
        assertTrue(VoiceMessages.decode("""{"type":"whatever"}""") is BridgeMessage.Invalid)
        assertTrue(VoiceMessages.decode("""{"type":"say","id":"t1"}""") is BridgeMessage.Invalid)
        assertTrue(VoiceMessages.decode("""{"type":"say","text":"x"}""") is BridgeMessage.Invalid)
        assertTrue(VoiceMessages.decode("""{"type":"done"}""") is BridgeMessage.Invalid)
        assertTrue(VoiceMessages.decode("""{"type":"error","id":"t1"}""") is BridgeMessage.Invalid)
    }

    @Test
    fun buildsBridgeUrlWithEncodedToken() {
        assertEquals("ws://h:8766/voice?token=a%2Bb", VoiceUrl.build(" ws://h:8766/voice ", "a+b"))
        assertEquals("wss://h/voice?x=1&token=t", VoiceUrl.build("wss://h/voice?x=1", "t"))
        assertNull(VoiceUrl.build("http://h:8766/voice", "t"))
        assertNull(VoiceUrl.build("ws://h:8766/voice", " "))
    }

    @Test
    fun backoffDoublesUpToCapAndResets() {
        val backoff = Backoff(initialSeconds = 1, maxSeconds = 10)
        assertEquals(listOf(1, 2, 4, 8, 10, 10), List(6) { backoff.nextDelaySeconds() })
        backoff.reset()
        assertEquals(1, backoff.nextDelaySeconds())
    }
}
