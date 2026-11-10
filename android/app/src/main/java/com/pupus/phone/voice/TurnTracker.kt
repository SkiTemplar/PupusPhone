package com.pupus.phone.voice

/**
 * Bookkeeping of the voice turn owned by [VoiceService]: the id sent to the
 * bridge, whether the bridge already said `done`, and the turn whose reply is
 * on screen. Pure so the reset and abort rules can be unit-tested.
 */
class TurnTracker {
    var currentId: String? = null
        private set

    /** True once the bridge finished (or the turn was dropped): speech drain ends the turn. */
    var isDone: Boolean = true
        private set

    private var replyId: String? = null

    val hasTurn: Boolean get() = currentId != null

    fun begin(id: String) {
        currentId = id
        isDone = false
    }

    fun isCurrent(id: String?): Boolean = id != null && id == currentId

    /** The bridge sent `done`: the turn ends once the queued speech is spoken. */
    fun markDone() {
        isDone = true
    }

    /** Drops the turn; returns the id that must be cancelled on the bridge, if any. */
    fun abort(): String? {
        val id = currentId
        currentId = null
        isDone = true
        return id
    }

    /** New conversation: like [abort], and the next reply no longer appends to the old one. */
    fun resetConversation(): String? {
        replyId = null
        return abort()
    }

    /** Text to show for a `say` frame: appended to [shown] while it belongs to the same turn. */
    fun appendReply(id: String, text: String, shown: String): String {
        val result = if (id == replyId) "$shown $text".trim() else text
        replyId = id
        return result
    }
}
