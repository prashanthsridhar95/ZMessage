package com.zyke.message.domain.sharing

/** How far a forwarded text got. Order matters: a later state replaces an earlier one, never the reverse. */
enum class ShareStatus {
    SENDING, SENT, DELIVERED, FAILED;

    val label: String
        get() = when (this) {
            SENDING -> "Sending"
            SENT -> "Sent"
            DELIVERED -> "Delivered"
            FAILED -> "Failed"
        }
}

/**
 * One line of the Recent log. It holds the service, the person's NAME, the time and the status, and nothing else: never the
 * code, the number or any message text. [test] marks the "Send a test message" texts, which have no service.
 */
data class ShareLogEntry(
    val id: String,
    val serviceId: String,
    val contactId: String,
    val contactName: String,
    val at: Long,
    val status: ShareStatus,
    val test: Boolean = false,
    /** The source's name at the time ("JioHotstar"); a name only. Blank for an entry saved before sources could be chosen. */
    val label: String = ""
)

object ShareLog {
    const val MAX_ENTRIES = 50
    private const val KEEP_MS = 30L * 24 * 60 * 60_000L

    /** The newest first, capped, with anything older than 30 days dropped. */
    fun add(log: List<ShareLogEntry>, entry: ShareLogEntry, now: Long = entry.at): List<ShareLogEntry> =
        (listOf(entry) + log.filter { it.id != entry.id }).filter { now - it.at <= KEEP_MS }.sortedByDescending { it.at }.take(MAX_ENTRIES)

    /** The state after a report arrives. A failed send stays failed; a delivered text stays delivered. */
    fun advance(current: ShareStatus, incoming: ShareStatus): ShareStatus = when {
        current == ShareStatus.FAILED || current == ShareStatus.DELIVERED -> current
        incoming == ShareStatus.FAILED -> ShareStatus.FAILED
        incoming.ordinal > current.ordinal -> incoming
        else -> current
    }

    fun update(log: List<ShareLogEntry>, id: String, incoming: ShareStatus): List<ShareLogEntry> =
        log.map { if (it.id == id) it.copy(status = advance(it.status, incoming)) else it }

    /** What the rate limit counts: every real attempt to that person lately, failed ones too. Test texts are not counted. */
    fun records(log: List<ShareLogEntry>): List<SentRecord> =
        log.filter { !it.test }.map { SentRecord(it.contactId, it.serviceId, it.at) }

    /** "Sent Netflix code to Sister" / "Couldn't send to Sister": also the notification wording. No code in either. */
    fun headline(entry: ShareLogEntry): String {
        val label = entry.label.ifEmpty { ShareServices.byId(entry.serviceId)?.label.orEmpty() }.ifEmpty { null }
        return when {
            entry.test -> if (entry.status == ShareStatus.FAILED) "Couldn't send to ${entry.contactName}"
                else if (label != null) "Sent a test $label code to ${entry.contactName}" else "Sent a test message to ${entry.contactName}"
            entry.status == ShareStatus.FAILED -> "Couldn't send to ${entry.contactName}"
            label == null -> "Sent a code to ${entry.contactName}"
            else -> "Sent $label code to ${entry.contactName}"
        }
    }
}
