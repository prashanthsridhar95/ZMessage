package com.zyke.message.domain.mms

/** One part of a picture message as the telephony provider lists it (content://mms/part): its row id, its content type and, for text, the text. */
data class MmsPart(val id: Long, val contentType: String, val text: String? = null, val seq: Int = 0)

/** One address row of a picture message (content://mms/<id>/addr): the number and its type (137 = from, 151 = to). */
data class MmsAddress(val address: String, val type: Int)

/**
 * The pure rules for reading picture (MMS) messages from the phone's MMS provider, which the data layer fills with rows:
 * which parts are text, which are pictures, who sent it, when, and the message id the rest of the app knows it by.
 * Nothing here touches Android.
 */
object MmsMessages {
    /** Type of the address row that names the sender. */
    const val ADDRESS_FROM = 137

    /** Most pictures shown for one message; later ones are left out so memory stays bounded. */
    const val MAX_IMAGES = 4

    /** Most text taken from one message. */
    const val MAX_TEXT = 20_000

    /**
     * SMS rows have positive ids and chat (RCS) messages have ids between -1 and about -2^31. MMS rows are placed far below both, so an
     * id never means two messages, and the same Long works wherever ids are kept (read and pinned lists, caches, learned templates).
     */
    const val ID_BASE: Long = 1L shl 40

    fun idFor(mmsRowId: Long): Long = -ID_BASE - mmsRowId.coerceAtLeast(0L)

    fun isMmsId(messageId: Long): Boolean = messageId <= -ID_BASE

    /** The row id in the MMS provider that [messageId] stands for; null when it is not an MMS id. */
    fun rowIdOf(messageId: Long): Long? = if (isMmsId(messageId)) -messageId - ID_BASE else null

    /** The provider stores the date of an MMS in seconds (an SMS in milliseconds); a value that is already in milliseconds is kept. */
    fun dateMillis(raw: Long): Long = if (raw in 0L until MILLIS_FLOOR) raw * 1000L else raw

    private const val MILLIS_FLOOR = 100_000_000_000L

    private fun isText(part: MmsPart) = part.contentType.substringBefore(';').trim().equals("text/plain", ignoreCase = true)

    private fun isImage(part: MmsPart) = part.contentType.substringBefore(';').trim().startsWith("image/", ignoreCase = true)

    /** The ids of the parts whose text has to be read: text parts that came without their text in the row. */
    fun textPartsToRead(parts: List<MmsPart>): List<MmsPart> = parts.filter { isText(it) && it.text == null }

    /** All text parts in their order, trimmed, empty ones dropped, joined by a line break. */
    fun text(parts: List<MmsPart>): String =
        parts.filter { isText(it) }.sortedWith(compareBy({ it.seq }, { it.id }))
            .mapNotNull { it.text?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n").take(MAX_TEXT)

    /** The picture parts in their order, at most [max]. */
    fun imagePartIds(parts: List<MmsPart>, max: Int = MAX_IMAGES): List<Long> =
        parts.filter { isImage(it) }.sortedWith(compareBy({ it.seq }, { it.id })).take(max).map { it.id }

    /** Whether the message carries any picture. */
    fun hasImage(parts: List<MmsPart>): Boolean = parts.any { isImage(it) }

    /** The sender: the "from" address, never the placeholder the provider writes when it is unknown. */
    fun sender(addresses: List<MmsAddress>): String =
        addresses.firstOrNull { it.type == ADDRESS_FROM && it.address.isNotBlank() && !it.address.startsWith(PLACEHOLDER) }?.address?.trim().orEmpty()

    private const val PLACEHOLDER = "insert-address-token"

    /** A message worth listing: it has text or a picture and a sender. */
    fun isUsable(sender: String, text: String, hasImage: Boolean) = sender.isNotEmpty() && (text.isNotEmpty() || hasImage)

    /** What the list shows for a picture message without any text. */
    const val PICTURE_ONLY_TEXT = "Picture message"
}
