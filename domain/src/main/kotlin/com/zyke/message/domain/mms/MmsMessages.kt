package com.zyke.message.domain.mms

/** One part of a picture message as the telephony provider lists it (content://mms/part): its row id, its content type and, for text, the text. */
data class MmsPart(val id: Long, val contentType: String, val text: String? = null, val seq: Int = 0, val charset: Int = 0)

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

    private fun baseType(part: MmsPart) = part.contentType.substringBefore(';').trim().lowercase()

    private fun isImage(part: MmsPart) = baseType(part).startsWith("image/")

    /** How a part's content is read as text. */
    enum class TextKind { TEXT, HTML, JSON, NONE }

    /**
     * Which parts can hold the words of a message. Plain text of any "text/" type (not a contact card), a JSON payload (a rich card
     * may be kept as "application/json" or "...+json"), and, for an odd content type, the part's own text column when it has
     * any. Layout (SMIL), pictures, sound, video and contact cards are never text.
     *
     * UNVERIFIED on a device: the real layout of a rich card in the MMS provider is not known; this accepts every shape we can think of.
     */
    fun textKind(part: MmsPart): TextKind {
        val type = baseType(part)
        return when {
            type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/") -> TextKind.NONE
            type.contains("smil") || type.contains("vcard") || type.contains("vcalendar") -> TextKind.NONE
            type == "text/html" -> TextKind.HTML
            type.startsWith("text/") -> TextKind.TEXT
            type.contains("json") -> TextKind.JSON
            else -> {
                val own = part.text?.trim().orEmpty()
                when {
                    own.isEmpty() -> TextKind.NONE
                    own.startsWith("{") || own.startsWith("[") -> TextKind.JSON
                    else -> TextKind.TEXT
                }
            }
        }
    }

    /** The parts whose text has to be read from the part's data: text or JSON parts that came without their text in the row. */
    fun textPartsToRead(parts: List<MmsPart>): List<MmsPart> =
        parts.filter { it.text == null && textKind(it) != TextKind.NONE }

    /** The Java charset name for the provider's "chset" column (an IANA MIB number); UTF-8 when absent or unknown. */
    fun charsetName(mib: Int): String = when (mib) {
        3 -> "US-ASCII"
        4 -> "ISO-8859-1"
        106 -> "UTF-8"
        1013 -> "UTF-16BE"
        1014 -> "UTF-16LE"
        1015 -> "UTF-16"
        2252 -> "windows-1252"
        else -> "UTF-8"
    }

    /** The words of one part, according to its kind. */
    private fun partText(part: MmsPart): String {
        val raw = part.text?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return when (textKind(part)) {
            TextKind.TEXT -> raw
            TextKind.HTML -> raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]*>"), " ")
                .replace(Regex("[ \\t]+"), " ").lines().joinToString("\n") { it.trim() }.trim()
            TextKind.JSON -> RbmCardText.strings(raw).joinToString("\n")
            TextKind.NONE -> ""
        }
    }

    /** All text parts in their order, trimmed, empty ones dropped, joined by a line break. */
    fun text(parts: List<MmsPart>): String =
        parts.sortedWith(compareBy({ it.seq }, { it.id }))
            .map { partText(it) }.filter { it.isNotEmpty() }
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

    /** Pixels a side a picture is read at: small on a phone with little memory. */
    fun maxSide(lite: Boolean): Int = if (lite) 512 else 1024

    /**
     * The power of two a picture of [width] x [height] is shrunk by when read, so that neither side falls below [maxSide] (the
     * decoder then lands between [maxSide] and twice that). 1 for a picture already small or whose size is unknown.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxSide && height / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** What the list shows for a picture message without any text. */
    const val PICTURE_ONLY_TEXT = "Picture message"
}
