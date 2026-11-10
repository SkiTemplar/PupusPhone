package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceVolumeTest {
    private class FakeMedia(override var current: Int, override val min: Int = 0, override val max: Int = 15) : MediaVolume {
        var isRefused = false
        val history = mutableListOf<Int>()

        override fun set(index: Int): Boolean {
            if (isRefused) return false
            current = index
            history.add(index)
            return true
        }
    }

    @Test
    fun ttsVolumeDefaultsToFullAndIsClamped() {
        assertEquals(1.0f, VoiceVolumeConfig.ttsVolume(VoiceVolumeConfig.DEFAULT_TTS_PERCENT))
        assertEquals(0.5f, VoiceVolumeConfig.ttsVolume(50))
        assertEquals(1.0f, VoiceVolumeConfig.ttsVolume(250))
        assertEquals(0.0f, VoiceVolumeConfig.ttsVolume(-3))
    }

    @Test
    fun raiseIsDisabledByDefault() {
        assertNull(VoiceVolumeConfig.boostTarget(1, 0, 15, VoiceVolumeConfig.DEFAULT_MIN_MEDIA_PERCENT))
    }

    @Test
    fun boostTargetRoundsUpToTheThreshold() {
        assertEquals(8, VoiceVolumeConfig.boostTarget(2, 0, 15, 50))
        assertNull(VoiceVolumeConfig.boostTarget(8, 0, 15, 50))
        assertNull(VoiceVolumeConfig.boostTarget(12, 0, 15, 50))
        assertEquals(15, VoiceVolumeConfig.boostTarget(0, 0, 15, 100))
        assertNull(VoiceVolumeConfig.boostTarget(0, 5, 5, 50))
    }

    @Test
    fun raisesWhileSpeakingAndRestoresAfterwards() {
        val media = FakeMedia(current = 3)
        val boost = MediaVolumeBoost(media)
        boost.raise(60)
        assertEquals(9, media.current)
        assertTrue(boost.isBoosting)
        boost.raise(60)
        boost.restore()
        assertEquals(3, media.current)
        assertFalse(boost.isBoosting)
        assertEquals(listOf(9, 3), media.history)
    }

    @Test
    fun keepsTheUsersChangeMadeDuringTheTurn() {
        val media = FakeMedia(current = 3)
        val boost = MediaVolumeBoost(media)
        boost.raise(60)
        media.current = 12
        boost.restore()
        assertEquals(12, media.current)
    }

    @Test
    fun doesNothingWhenLoudEnoughOrRefused() {
        val loud = FakeMedia(current = 14)
        MediaVolumeBoost(loud).apply { raise(60); restore() }
        assertTrue(loud.history.isEmpty())
        val refused = FakeMedia(current = 1).also { it.isRefused = true }
        val boost = MediaVolumeBoost(refused)
        boost.raise(60)
        assertFalse(boost.isBoosting)
        boost.restore()
        assertEquals(1, refused.current)
    }
}

class TurnAudioTest {
    private class Volume : StreamVolume {
        val muted = mutableSetOf<Int>()
        override fun isMuted(stream: Int) = stream in muted
        override fun setMuted(stream: Int, isMuted: Boolean): Boolean {
            if (isMuted) muted.add(stream) else muted.remove(stream)
            return true
        }
    }

    private class Store : MutedStreamStore {
        var saved: Set<Int> = emptySet()
        override fun load() = saved
        override fun save(streams: Set<Int>) {
            saved = streams
        }
    }

    private class Media(override var current: Int) : MediaVolume {
        override val min = 0
        override val max = 15
        override fun set(index: Int): Boolean {
            current = index
            return true
        }
    }

    @Test
    fun nothingStaysMutedOnceTheAssistantSpeaks() {
        val volume = Volume()
        val muter = StreamMuter(volume, Store(), listOf(3, 5, 1))
        val audio = TurnAudio(muter, MediaVolumeBoost(Media(current = 10)))
        muter.mute()
        assertEquals(setOf(3, 5, 1), volume.muted)
        audio.beforeSpeech(minMediaPercent = 0)
        assertTrue(volume.muted.isEmpty())
        assertFalse(muter.isMuting)
    }

    @Test
    fun releaseRestoresTheRaisedMediaVolume() {
        val media = Media(current = 2)
        val audio = TurnAudio(StreamMuter(Volume(), Store(), listOf(3)), MediaVolumeBoost(media))
        audio.beforeSpeech(minMediaPercent = 50)
        assertEquals(8, media.current)
        audio.release()
        assertEquals(2, media.current)
    }
}
