package com.pupus.phone.voice

/** One exchange: what the user said, the full assistant reply and the bridge status lines. */
data class ConversationTurn(
    val id: String,
    val userText: String,
    val reply: String = "",
    val statuses: List<String> = emptyList(),
)

/**
 * Immutable history of the current conversation, newest turn last. Every
 * operation returns a new instance; at most [maxTurns] turns are kept (the
 * oldest are dropped). Frames for an unknown turn id are ignored.
 */
class ConversationHistory private constructor(
    val turns: List<ConversationTurn>,
    val maxTurns: Int,
) {
    constructor(maxTurns: Int = DEFAULT_MAX_TURNS) : this(emptyList(), maxTurns)

    init {
        require(maxTurns > 0) { "maxTurns must be positive" }
    }

    val isEmpty: Boolean get() = turns.isEmpty()

    /** Starts a turn for a sent utterance. A repeated id replaces the old turn. */
    fun appendUtterance(id: String, text: String): ConversationHistory {
        val kept = turns.filterNot { it.id == id }
        return copy(kept + ConversationTurn(id, text.trim())).trimmed()
    }

    /** Appends the text of a `say` frame to the reply of turn [id]. */
    fun appendSay(id: String, text: String): ConversationHistory {
        val piece = text.trim()
        if (piece.isEmpty()) return this
        return update(id) { turn ->
            val reply = if (turn.reply.isEmpty()) piece else "${turn.reply} $piece"
            turn.copy(reply = reply)
        }
    }

    /** Adds a status line (progress or error) to turn [id]; consecutive duplicates are skipped. */
    fun appendStatus(id: String, text: String): ConversationHistory {
        val line = text.trim()
        if (line.isEmpty()) return this
        return update(id) { turn ->
            if (turn.statuses.lastOrNull() == line) turn else turn.copy(statuses = turn.statuses + line)
        }
    }

    /** New conversation: an empty history with the same cap. */
    fun reset(): ConversationHistory = ConversationHistory(maxTurns)

    private fun update(id: String, transform: (ConversationTurn) -> ConversationTurn): ConversationHistory {
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0) return this
        val updated = transform(turns[index])
        if (updated == turns[index]) return this
        return copy(turns.toMutableList().also { it[index] = updated })
    }

    private fun trimmed(): ConversationHistory =
        if (turns.size <= maxTurns) this else copy(turns.takeLast(maxTurns))

    private fun copy(newTurns: List<ConversationTurn>) = ConversationHistory(newTurns.toList(), maxTurns)

    companion object {
        const val DEFAULT_MAX_TURNS = 50
    }
}
