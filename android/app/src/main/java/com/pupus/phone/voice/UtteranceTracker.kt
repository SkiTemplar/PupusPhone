package com.pupus.phone.voice

/**
 * Tracks queued TTS utterances per turn. Ids carry the turn generation, so a late
 * `onDone` from an utterance cancelled by [newTurn] cannot end the next turn.
 * Keeps the text of each pending utterance, so a lost engine can be replaced and the
 * unspoken text queued again ([takeForRetry]).
 * Not thread-safe: use from the main thread only.
 */
class UtteranceTracker {
    private var turn = 0
    private var counter = 0
    private val pending = LinkedHashMap<String, String>()

    val isBusy: Boolean get() = pending.isNotEmpty()

    /** Registers a new utterance of the current turn and returns its TTS id. */
    fun next(text: String = ""): String {
        counter++
        val id = "t$turn-u$counter"
        pending[id] = text
        return id
    }

    /** True while [id] belongs to the current turn and has not finished. */
    fun owns(id: String?): Boolean = id != null && pending.containsKey(id)

    /** Drops every pending utterance; their late callbacks will be ignored. */
    fun newTurn() {
        turn++
        pending.clear()
    }

    /**
     * The engine was lost: returns the unfinished texts in queue order and forgets their ids,
     * so late callbacks of the old engine are ignored and the turn does not drain.
     */
    fun takeForRetry(): List<String> {
        val texts = pending.values.filter { it.isNotBlank() }
        newTurn()
        return texts
    }

    /** Returns true when [id] belonged to the current turn and was its last pending utterance. */
    fun finish(id: String?): Boolean {
        if (id == null || pending.remove(id) == null) return false
        return pending.isEmpty()
    }
}
