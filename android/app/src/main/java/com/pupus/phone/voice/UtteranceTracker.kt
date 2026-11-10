package com.pupus.phone.voice

/**
 * Tracks queued TTS utterances per turn. Ids carry the turn generation, so a late
 * `onDone` from an utterance cancelled by [newTurn] cannot end the next turn.
 * Not thread-safe: use from the main thread only.
 */
class UtteranceTracker {
    private var turn = 0
    private var counter = 0
    private val pending = HashSet<String>()

    val isBusy: Boolean get() = pending.isNotEmpty()

    /** Registers a new utterance of the current turn and returns its TTS id. */
    fun next(): String {
        counter++
        val id = "t$turn-u$counter"
        pending.add(id)
        return id
    }

    /** Drops every pending utterance; their late callbacks will be ignored. */
    fun newTurn() {
        turn++
        pending.clear()
    }

    /** Returns true when [id] belonged to the current turn and was its last pending utterance. */
    fun finish(id: String?): Boolean {
        if (id == null || !pending.remove(id)) return false
        return pending.isEmpty()
    }
}
