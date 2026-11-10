package com.pupus.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMuterTest {
    private class FakeVolume(
        val muted: MutableSet<Int> = mutableSetOf(),
        val refused: Set<Int> = emptySet(),
    ) : StreamVolume {
        override fun isMuted(stream: Int) = stream in muted

        override fun setMuted(stream: Int, isMuted: Boolean): Boolean {
            if (stream in refused) return false
            if (isMuted) muted.add(stream) else muted.remove(stream)
            return true
        }
    }

    private class FakeStore(var saved: Set<Int> = emptySet()) : MutedStreamStore {
        override fun load() = saved

        override fun save(streams: Set<Int>) {
            saved = streams
        }
    }

    private val streams = listOf(MUSIC, NOTIFICATION, SYSTEM)

    @Test
    fun muteThenRestoreLeavesEverythingAudible() {
        val volume = FakeVolume()
        val store = FakeStore()
        val muter = StreamMuter(volume, store, streams)
        muter.mute()
        assertEquals(setOf(MUSIC, NOTIFICATION, SYSTEM), volume.muted)
        assertEquals(setOf(MUSIC, NOTIFICATION, SYSTEM), store.saved)
        muter.restore()
        assertTrue(volume.muted.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertFalse(muter.isMuting)
    }

    @Test
    fun streamMutedByTheUserStaysMuted() {
        val volume = FakeVolume(muted = mutableSetOf(NOTIFICATION))
        val muter = StreamMuter(volume, FakeStore(), streams)
        muter.mute()
        muter.restore()
        assertEquals(setOf(NOTIFICATION), volume.muted)
    }

    @Test
    fun repeatedMuteForEverySegmentRestoresOnce() {
        val volume = FakeVolume()
        val muter = StreamMuter(volume, FakeStore(), streams)
        repeat(5) { muter.mute() }
        muter.restore()
        muter.restore()
        assertTrue(volume.muted.isEmpty())
    }

    @Test
    fun refusedStreamIsNotTrackedAndOthersStillMute() {
        val volume = FakeVolume(refused = setOf(SYSTEM))
        val store = FakeStore()
        val muter = StreamMuter(volume, store, streams)
        muter.mute()
        assertEquals(setOf(MUSIC, NOTIFICATION), volume.muted)
        assertEquals(setOf(MUSIC, NOTIFICATION), store.saved)
        muter.restore()
        assertTrue(volume.muted.isEmpty())
    }

    @Test
    fun recoverUnmutesWhatACrashedProcessLeftMuted() {
        val volume = FakeVolume()
        val store = FakeStore()
        StreamMuter(volume, store, streams).mute() // the process dies without restore()
        assertEquals(3, volume.muted.size)
        StreamMuter(volume, store, streams).recover()
        assertTrue(volume.muted.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun failedUnmuteIsKeptForTheNextRecovery() {
        val volume = FakeVolume(muted = mutableSetOf(MUSIC))
        val store = FakeStore(saved = setOf(MUSIC))
        val stuck = object : StreamVolume by volume {
            override fun setMuted(stream: Int, isMuted: Boolean) = false
        }
        StreamMuter(stuck, store, streams).recover()
        assertEquals(setOf(MUSIC), store.saved)
        StreamMuter(volume, store, streams).recover()
        assertTrue(volume.muted.isEmpty())
    }

    private companion object {
        const val MUSIC = 3
        const val NOTIFICATION = 5
        const val SYSTEM = 1
    }
}
