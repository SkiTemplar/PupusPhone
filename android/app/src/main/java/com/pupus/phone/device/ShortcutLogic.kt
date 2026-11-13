package com.pupus.phone.device

import org.json.JSONArray
import org.json.JSONObject

/*
 * Pure logic behind the media, notification and contact endpoints (no Android classes), so it is
 * covered by JVM unit tests. The Android readers in PersonalData.kt only extract these values.
 */

data class SessionView(val pkg: String, val supportsPlaySearch: Boolean)

object SessionPicker {
    /**
     * Index of the session for `play_search`: the requested package when it has a session that
     * accepts searches; without a request, the first other app (system priority order) that does.
     */
    fun pick(sessions: List<SessionView>, requested: String?, ownPackage: String): Int? {
        val candidates = sessions.withIndex().filter { it.value.pkg != ownPackage && it.value.supportsPlaySearch }
        val match = if (requested != null) candidates.firstOrNull { it.value.pkg == requested } else candidates.firstOrNull()
        return match?.index
    }
}

data class MessageLine(val sender: String?, val text: String)

data class NotificationFields(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val lines: List<String>,
    val messages: List<MessageLine>,
    val postedAt: Long,
    val ongoing: Boolean,
    val groupSummary: Boolean,
)

object NotificationDigest {
    const val MAX_NOTIFICATIONS = 30
    const val MAX_TEXT = 500
    private const val MAX_LINES = 5

    /** Most useful text: last chat messages, then expanded text, then inbox lines, then the plain text. */
    fun body(f: NotificationFields): String? {
        val chat = f.messages.takeLast(MAX_LINES).joinToString("\n") { m -> m.sender?.let { "$it: ${m.text}" } ?: m.text }
        val inbox = f.lines.takeLast(MAX_LINES).joinToString("\n")
        val best = listOf(chat, f.bigText, inbox, f.text).firstOrNull { !it.isNullOrBlank() } ?: return null
        return if (best.length <= MAX_TEXT) best else best.take(MAX_TEXT - 1) + "…"
    }

    fun toJson(all: List<NotificationFields>, ownPackage: String, pkg: String?): JSONObject {
        val items = all.asSequence()
            .filter { !it.ongoing && !it.groupSummary && it.pkg != ownPackage }
            .filter { pkg == null || it.pkg == pkg }
            .sortedByDescending { it.postedAt }
            .take(MAX_NOTIFICATIONS)
            .map { f ->
                JSONObject()
                    .put("key", f.key)
                    .put("package", f.pkg)
                    .put("app", f.app)
                    .put("title", f.title ?: JSONObject.NULL)
                    .put("text", body(f) ?: JSONObject.NULL)
                    .put("postedAt", f.postedAt)
            }
        return JSONObject().put("notifications", JSONArray(items.toList()))
    }
}

data class PhoneRow(val contactId: Long, val name: String, val number: String, val label: String?)

object ContactGrouping {
    const val MAX_CONTACTS = 10

    /** Groups phone rows by contact (first-seen order) and drops numbers repeated with other formatting. */
    fun toJson(rows: List<PhoneRow>): JSONObject {
        val contacts = rows.groupBy { it.contactId }.values.take(MAX_CONTACTS).map { group ->
            val numbers = group.distinctBy { digits(it.number) }.map { row ->
                JSONObject().put("number", row.number).put("label", row.label ?: JSONObject.NULL)
            }
            JSONObject().put("name", group.first().name).put("numbers", JSONArray(numbers))
        }
        return JSONObject().put("contacts", JSONArray(contacts))
    }

    private fun digits(number: String): String = number.filter { it.isDigit() }.takeLast(9)
}
