package com.pupus.phone.voice

/** Volume operations used by [StreamMuter]; the Android implementation wraps AudioManager. */
interface StreamVolume {
    fun isMuted(stream: Int): Boolean

    /** Returns false when the platform refused the change (e.g. Do Not Disturb policy). */
    fun setMuted(stream: Int, isMuted: Boolean): Boolean
}

/** Persists the streams muted by us, so a crash cannot leave them muted for good. */
interface MutedStreamStore {
    fun load(): Set<Int>
    fun save(streams: Set<Int>)
}

/**
 * Mute bookkeeping for the recognizer earcons. Only streams that were audible and that
 * this class muted are unmuted later: a stream the user had muted stays muted.
 * [mute] and [restore] are idempotent. Not thread-safe: use from one thread.
 */
class StreamMuter(
    private val volume: StreamVolume,
    private val store: MutedStreamStore,
    private val streams: List<Int>,
) {
    private var muted: Set<Int> = emptySet()

    val isMuting: Boolean get() = muted.isNotEmpty()

    /** Unmutes whatever a previous process left muted (crash or kill while listening). */
    fun recover() {
        val leftover = store.load()
        if (leftover.isEmpty()) return
        muted = muted + leftover
        restore()
    }

    fun mute() {
        val candidates = streams.filter { it !in muted && !volume.isMuted(it) }
        if (candidates.isEmpty()) return
        // Saved before touching the volume: a crash in between must still be recoverable.
        store.save(muted + candidates)
        val done = candidates.filter { volume.setMuted(it, true) }
        muted = muted + done
        store.save(muted)
    }

    fun restore() {
        if (muted.isEmpty()) return
        val failed = muted.filterNot { volume.setMuted(it, false) }.toSet()
        muted = failed
        store.save(failed)
    }
}
