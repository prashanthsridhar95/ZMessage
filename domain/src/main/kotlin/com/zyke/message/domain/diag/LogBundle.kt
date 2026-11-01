package com.zyke.message.domain.diag

/** One log file's text, with the name it is shown under in the bundle. */
data class LogSection(val name: String, val text: String)

/**
 * Puts the log files into one text a person can send: a header (app, phone, Android, time), then each file in the
 * order given (oldest first). When the whole is over the limit the NEWEST content wins: the header stays, the oldest
 * lines go. Pure text work; it knows nothing about files, so it can run when the rest of the app cannot.
 */
object LogBundle {
    const val DEFAULT_MAX_CHARS = 1_500_000
    const val TRIMMED_NOTE = "[earlier lines left out to keep this small]\n"

    fun build(header: List<String>, sections: List<LogSection>, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val head = buildString {
            append("Zyke Message log\n")
            header.forEach { append(it).append('\n') }
            append('\n')
        }
        val body = sections.filter { it.text.isNotBlank() }.joinToString("") { section ->
            "===== ${section.name} =====\n" + section.text.trimEnd('\n') + "\n\n"
        }
        if (head.length + body.length <= maxChars) return head + body
        val room = (maxChars - head.length - TRIMMED_NOTE.length).coerceAtLeast(0)
        var tail = body.takeLast(room)
        // Start on a whole line, not in the middle of one.
        val firstBreak = tail.indexOf('\n')
        if (body.length > room && firstBreak in 0 until tail.length - 1) tail = tail.substring(firstBreak + 1)
        return head + TRIMMED_NOTE + tail
    }

    /** True when there is something worth sending. */
    fun hasContent(sections: List<LogSection>): Boolean = sections.any { it.text.isNotBlank() }
}
