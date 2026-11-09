package com.zyke.message.domain.mms

/** One entry of a messaging-style notification (the array a chat app puts in Notification.EXTRA_MESSAGES): its text and time (0 when absent). */
data class NotificationEntry(val text: String, val time: Long = 0L)

/** What a chat notification carries, as plain strings; null or empty for what is absent. */
data class NotificationFields(
    val title: String? = null,
    val conversationTitle: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val textLines: List<String> = emptyList(),
    val entries: List<NotificationEntry> = emptyList()
)

/** A message worth keeping from a notification: who it is from (the conversation's name), its text and when. */
data class CapturedChat(val sender: String, val body: String, val at: Long)

/**
 * Works out the messages in a chat notification. A rich (RCS) message reaches Zyke only as the text of such a notification: the
 * banner image and the buttons are not in it. The short text of a notification may be cut to one line while the expanded text,
 * the lines of an inbox-style notification or the messaging-style entries hold the whole of it, so the fullest is taken.
 */
object RichNotificationText {
    /** Most messaging-style entries taken from one notification. */
    const val MAX_ENTRIES = 5

    /** The sender is the conversation's name when there is one, else the title. Null when neither says. */
    fun sender(f: NotificationFields): String? =
        f.conversationTitle?.trim()?.takeIf { it.isNotEmpty() } ?: f.title?.trim()?.takeIf { it.isNotEmpty() }

    /** The messages in the notification, oldest first; empty when it holds no text or no sender. [postTime] dates what has no time of its own. */
    fun capture(f: NotificationFields, postTime: Long): List<CapturedChat> {
        val sender = sender(f) ?: return emptyList()
        val entries = f.entries.filter { it.text.isNotBlank() }.takeLast(MAX_ENTRIES)
        if (entries.isNotEmpty()) {
            val lastIndex = entries.lastIndex
            return entries.mapIndexed { index, entry ->
                // The entries are the messages themselves; only the newest can be a card whose title sits in the notification's title.
                val body = if (index == lastIndex) fuller(entry.text.trim(), f) else entry.text.trim()
                CapturedChat(sender, if (index == lastIndex) withHeader(sender, f.title, body) else body, if (entry.time > 0) entry.time else postTime)
            }
        }
        val body = fuller("", f)
        if (body.isEmpty()) return emptyList()
        return listOf(CapturedChat(sender, withHeader(sender, f.title, body), postTime))
    }

    /** The longest of [first], the expanded text, the joined lines and the short text; a shorter one never replaces a longer that holds it. */
    private fun fuller(first: String, f: NotificationFields): String {
        val candidates = listOf(
            first,
            f.bigText.orEmpty().trim(),
            f.textLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
            f.text.orEmpty().trim()
        ).filter { it.isNotEmpty() }
        // With a messaging-style entry the fuller text must hold that entry, or it is some other message's text.
        return candidates.filter { first.isEmpty() || it == first || it.contains(first) }.maxByOrNull { it.length }.orEmpty()
    }

    /** A card's own title ("Account Balance") is put in front of its text when the notification gives it apart from the sender's name. */
    private fun withHeader(sender: String, title: String?, body: String): String {
        val header = title?.trim().orEmpty()
        if (header.isEmpty() || header.equals(sender, ignoreCase = true) || body.contains(header, ignoreCase = true)) return body
        return "$header\n$body"
    }
}
