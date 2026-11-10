package com.zyke.message.domain.mms

/** What the developer dump says about one part of a picture message: never its bytes. */
data class MmsPartInfo(
    val contentType: String,
    /** Bytes read from the part's data (up to the limit), or -1 when it was not read (a picture, or no data). */
    val size: Int,
    val sizeCapped: Boolean,
    val hadText: Boolean,
    val hadData: Boolean,
    val textHead: String
)

/**
 * The text a developer can share so the real layout of a rich card in the MMS provider can be handled. Built in memory only. It
 * lists each part's content type, size and whether it had text or data, the first [HEAD] characters of any text, and how many
 * address rows of each type there were. No message id and no picture bytes. It may contain the message text.
 */
object MmsStructureDump {
    const val HEAD = 300
    const val HEADER = "Developer structure dump, may contain the message text"

    fun build(sender: String, parts: List<MmsPartInfo>, addressTypes: Map<Int, Int>): String = buildString {
        appendLine(HEADER)
        appendLine("Sender: $sender")
        appendLine("Parts: ${parts.size}")
        parts.forEachIndexed { index, p ->
            val size = when {
                p.size < 0 -> "not read"
                p.sizeCapped -> "${p.size}+ bytes"
                else -> "${p.size} bytes"
            }
            appendLine("[$index] type=${p.contentType.ifEmpty { "(none)" }} size=$size text=${if (p.hadText) "yes" else "no"} data=${if (p.hadData) "yes" else "no"}")
            if (p.textHead.isNotEmpty()) appendLine("    text: " + p.textHead.take(HEAD).replace("\n", "\\n"))
        }
        appendLine("Address rows by type: " + if (addressTypes.isEmpty()) "none" else addressTypes.toSortedMap().entries.joinToString(", ") { "${it.key} x${it.value}" })
    }
}
