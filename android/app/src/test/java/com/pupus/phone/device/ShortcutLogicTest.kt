package com.pupus.phone.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutLogicTest {
    private val own = "com.pupus.phone"

    @Test
    fun picksTheRequestedSessionOnlyIfItAcceptsSearches() {
        val sessions = listOf(
            SessionView(own, true),
            SessionView("com.google.android.youtube", true),
            SessionView("com.spotify.music", true),
        )
        assertEquals(2, SessionPicker.pick(sessions, "com.spotify.music", own))
        assertEquals(1, SessionPicker.pick(sessions, null, own))
        assertNull(SessionPicker.pick(listOf(SessionView("com.spotify.music", false)), "com.spotify.music", own))
        assertNull(SessionPicker.pick(listOf(SessionView(own, true)), null, own))
        assertNull(SessionPicker.pick(sessions, "com.apple.android.music", own))
    }

    private fun notification(
        key: String,
        postedAt: Long,
        pkg: String = "com.whatsapp",
        text: String? = "hola",
        ongoing: Boolean = false,
        summary: Boolean = false,
        messages: List<MessageLine> = emptyList(),
        bigText: String? = null,
        lines: List<String> = emptyList(),
    ) = NotificationFields(key, pkg, "WhatsApp", "Mamá", text, bigText, lines, messages, postedAt, ongoing, summary)

    @Test
    fun digestFiltersSortsAndCapsNotifications() {
        val all = listOf(
            notification("old", 1),
            notification("new", 5),
            notification("music", 9, pkg = "com.spotify.music", ongoing = true),
            notification("group", 8, summary = true),
            notification("mine", 7, pkg = own),
            notification("mail", 6, pkg = "com.google.android.gm"),
        )
        val json = NotificationDigest.toJson(all, own, null).getJSONArray("notifications")
        assertEquals(listOf("mail", "new", "old"), (0 until json.length()).map { json.getJSONObject(it).getString("key") })
        val onlyChats = NotificationDigest.toJson(all, own, "com.whatsapp").getJSONArray("notifications")
        assertEquals(2, onlyChats.length())
        val many = (1..40).map { notification("n$it", it.toLong()) }
        assertEquals(NotificationDigest.MAX_NOTIFICATIONS, NotificationDigest.toJson(many, own, null).getJSONArray("notifications").length())
    }

    @Test
    fun bodyPrefersChatMessagesThenBigTextThenLinesThenText() {
        val chat = (1..7).map { MessageLine(if (it % 2 == 0) "Mamá" else null, "m$it") }
        assertEquals("m3\nMamá: m4\nm5\nMamá: m6\nm7", NotificationDigest.body(notification("a", 1, messages = chat)))
        assertEquals("largo", NotificationDigest.body(notification("b", 1, bigText = "largo", lines = listOf("x"))))
        assertEquals("l1\nl2", NotificationDigest.body(notification("c", 1, lines = listOf("l1", "l2"))))
        assertEquals("hola", NotificationDigest.body(notification("d", 1)))
        assertNull(NotificationDigest.body(notification("e", 1, text = " ")))
        val long = NotificationDigest.body(notification("f", 1, text = "x".repeat(900)))!!
        assertEquals(NotificationDigest.MAX_TEXT, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun contactsAreGroupedAndNumbersDeduplicated() {
        val rows = listOf(
            PhoneRow(1, "Mamá", "+34 612 345 678", "Móvil"),
            PhoneRow(2, "Papá", "699 111 222", "Móvil"),
            PhoneRow(1, "Mamá", "612345678", "Móvil"),
            PhoneRow(1, "Mamá", "91 123 45 67", "Casa"),
        )
        val contacts = ContactGrouping.toJson(rows).getJSONArray("contacts")
        assertEquals(2, contacts.length())
        val mama = contacts.getJSONObject(0)
        assertEquals("Mamá", mama.getString("name"))
        assertEquals(2, mama.getJSONArray("numbers").length())
        assertEquals("Casa", mama.getJSONArray("numbers").getJSONObject(1).getString("label"))
        val many = (1L..15L).map { PhoneRow(it, "C$it", "6000000$it", null) }
        assertEquals(ContactGrouping.MAX_CONTACTS, ContactGrouping.toJson(many).getJSONArray("contacts").length())
    }
}
