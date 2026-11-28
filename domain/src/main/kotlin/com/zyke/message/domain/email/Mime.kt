package com.zyke.message.domain.email

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A small, forgiving reader for the one kind of mail the app reads: a bank's alert. Pure Kotlin, no Android, so it is tested on
 * a plain JVM. It decodes what a message header and body are written in (RFC 2047 words, quoted-printable, base64, UTF-8,
 * ISO-8859-1, windows-1252, nested multipart) and returns the text, never an attachment. Everything is bounded: at most
 * [MAX_DEPTH] levels, [MAX_PARTS] parts and [MAX_TEXT] characters, and nothing here throws on hostile input.
 */
object Mime {
    const val MAX_DEPTH = 5
    const val MAX_PARTS = 40
    const val MAX_TEXT = 100_000

    /** The headers (names in lower case; the first of a repeated header wins; folded lines joined) and the readable text of a message. */
    class Parsed(val headers: Map<String, String>, val text: String)

    private val PARAM = Regex("""(?i);\s*([a-z0-9_*-]+)\s*=\s*("([^"]*)"|[^;\s]+)""")
    private val ENCODED_WORD = Regex("""=\?([^?\s]+)\?([bBqQ])\?([^?\s]*)\?=""")
    private val WORD_GAP = Regex("""(?<=\?=)\s+(?==\?)""")
    private val ANGLE_ADDRESS = Regex("""<\s*([^<>\s]+@[^<>\s]+)\s*>""")
    private val BARE_ADDRESS = Regex("""[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+""")
    private val COMMENT = Regex("""\([^)]*\)""")

    /** Parses a whole message (as fetched, possibly cut short) into its headers and the text a person would read. */
    fun parse(raw: ByteArray): Parsed {
        return try {
            val message = String(raw, Charsets.ISO_8859_1)
            val (headerBlock, _) = split(message)
            val headers = parseHeaders(headerBlock)
            val budget = intArrayOf(MAX_PARTS)
            val found = readPart(message, 0, budget)
            Parsed(headers, chooseText(found).take(MAX_TEXT))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Parsed(emptyMap(), "")
        }
    }

    /** Header lines of [block] as a map of lower-case name to value, unfolded. The first occurrence of a name is kept. */
    fun parseHeaders(block: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var name: String? = null
        val value = StringBuilder()
        fun flush() {
            val n = name ?: return
            if (n !in out) out[n] = value.toString().trim()
            name = null
            value.clear()
        }
        for (line in block.split("\r\n", "\n")) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && name != null) {
                value.append(' ').append(line.trim())
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            flush()
            name = line.substring(0, colon).trim().lowercase()
            value.append(line.substring(colon + 1).trim())
        }
        flush()
        return out
    }

    /** Splits at the first empty line. A message with no body gives an empty body. */
    private fun split(message: String): Pair<String, String> {
        val crlf = message.indexOf("\r\n\r\n")
        val lf = message.indexOf("\n\n")
        val (at, gap) = when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf to 4
            lf >= 0 -> lf to 2
            else -> return message to ""
        }
        return message.substring(0, at) to message.substring(at + gap)
    }

    private class Found(val plain: String?, val html: String?)

    private fun readPart(part: String, depth: Int, budget: IntArray): Found {
        if (depth > MAX_DEPTH || budget[0] <= 0) return Found(null, null)
        budget[0]--
        val (headerBlock, body) = split(part)
        val headers = parseHeaders(headerBlock)
        val type = headers["content-type"].orEmpty()
        val main = type.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        if (headers["content-disposition"].orEmpty().trimStart().startsWith("attachment", ignoreCase = true)) return Found(null, null)
        if (main.startsWith("multipart/")) {
            val boundary = param(type, "boundary") ?: return Found(null, null)
            var plain: String? = null
            var html: String? = null
            for (child in children(body, boundary)) {
                val f = readPart(child, depth + 1, budget)
                if (plain == null && !f.plain.isNullOrBlank()) plain = f.plain
                if (html == null && !f.html.isNullOrBlank()) html = f.html
            }
            return Found(plain, html)
        }
        if (main != "text/plain" && main != "text/html") return Found(null, null)
        val bytes = decodeTransfer(body, headers["content-transfer-encoding"].orEmpty())
        val text = decodeCharset(bytes, param(type, "charset"))
        return if (main == "text/html") Found(null, HtmlText.strip(text)) else Found(text.replace("\r\n", "\n").trim(), null)
    }

    /** The plain part when it says something, else the page's text. A plain stub ("view this in your browser") loses to the page. */
    private fun chooseText(found: Found): String {
        val plain = found.plain.orEmpty()
        val html = found.html.orEmpty()
        return when {
            plain.length >= MIN_PLAIN && plain.any { it.isDigit() } -> plain
            html.isNotBlank() -> html
            else -> plain
        }
    }

    private fun children(body: String, boundary: String): List<String> {
        val marker = "--$boundary"
        val out = ArrayList<String>()
        var start = -1
        var pos = 0
        while (pos <= body.length) {
            val at = body.indexOf(marker, pos)
            if (at < 0) break
            val lineStart = at == 0 || body[at - 1] == '\n'
            if (!lineStart) { pos = at + marker.length; continue }
            if (start >= 0) out += body.substring(start, at).trimEnd('\r', '\n')
            val after = at + marker.length
            if (body.startsWith("--", after)) { start = -1; break }
            val eol = body.indexOf('\n', after)
            if (eol < 0) { start = -1; break }
            start = eol + 1
            pos = start
        }
        // A message cut short by the size limit has no closing boundary: what was read of the last part is still used.
        if (start >= 0 && start < body.length) out += body.substring(start)
        return out
    }

    /** The value of a header parameter (`charset`, `boundary`), without quotes; null when absent. */
    fun param(header: String, name: String): String? {
        for (m in PARAM.findAll(header)) {
            if (m.groupValues[1].equals(name, ignoreCase = true)) return m.groupValues[3].ifEmpty { m.groupValues[2] }.trim()
        }
        return null
    }

    private fun decodeTransfer(body: String, encoding: String): ByteArray = when (encoding.trim().lowercase()) {
        "base64" -> Base64Lenient.decode(body)
        "quoted-printable" -> QuotedPrintable.decode(body)
        else -> body.toByteArray(Charsets.ISO_8859_1)
    }

    /** Bytes to text. An unnamed or unknown charset is tried as UTF-8 first, then windows-1252, so nothing is dropped. */
    fun decodeCharset(bytes: ByteArray, name: String?): String {
        val charset = name?.trim('"', ' ')?.let { runCatching { Charset.forName(it) }.getOrNull() }
        if (charset != null && charset != Charsets.UTF_8) return String(bytes, charset)
        val strict = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    /** A header's words as plain text: `=?UTF-8?B?...?=` and `=?ISO-8859-1?Q?...?=` decoded, other text kept. */
    fun decodeHeader(value: String): String {
        if ("=?" !in value) return value.trim()
        val joined = WORD_GAP.replace(value, "")
        return ENCODED_WORD.replace(joined) { m ->
            val bytes = when (m.groupValues[2].lowercase()) {
                "b" -> Base64Lenient.decode(m.groupValues[3])
                else -> QuotedPrintable.decode(m.groupValues[3].replace('_', ' '))
            }
            decodeCharset(bytes, m.groupValues[1].substringBefore('*'))
        }.trim()
    }

    /** The bare lower-case address in a From header: `"HDFC Bank" <alerts@hdfcbank.net>` gives `alerts@hdfcbank.net`; "" when none. */
    fun addressOf(fromHeader: String): String {
        val cleaned = COMMENT.replace(decodeHeader(fromHeader), "")
        val inAngles = ANGLE_ADDRESS.find(cleaned)?.groupValues?.get(1)
        val found = inAngles ?: BARE_ADDRESS.find(cleaned)?.value
        return found?.trim('<', '>', ' ', '"')?.lowercase().orEmpty()
    }

    /** The Date header as epoch milliseconds; null when it cannot be read. */
    fun parseDate(value: String): Long? {
        val cleaned = COMMENT.replace(value, "").trim().replace(Regex("\\s+"), " ")
        return runCatching { OffsetDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    private const val MIN_PLAIN = 30
}

/** Quoted-printable (RFC 2045), tolerant: a stray `=` is kept, soft line breaks are removed. */
object QuotedPrintable {
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '=') { out.write(c.code and 0xFF); i++; continue }
            when {
                i + 1 < text.length && text[i + 1] == '\n' -> i += 2
                i + 2 < text.length && text[i + 1] == '\r' && text[i + 2] == '\n' -> i += 3
                i + 2 < text.length && hex(text[i + 1]) >= 0 && hex(text[i + 2]) >= 0 -> {
                    out.write(hex(text[i + 1]) * HEX_BASE + hex(text[i + 2]))
                    i += 3
                }
                else -> { out.write(c.code and 0xFF); i++ }
            }
        }
        return out.toByteArray()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    private const val HEX_BASE = 16
}

/** Base64 that skips anything that is not part of the alphabet (line breaks, spaces) and stops at padding. */
object Base64Lenient {
    private const val BITS = 6
    private const val BYTE_MASK = 0xFF

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '=' -> break
                else -> continue
            }
            buffer = (buffer shl BITS) or v
            bits += BITS
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and BYTE_MASK)
            }
        }
        return out.toByteArray()
    }
}

/** HTML to the text a person reads: tags, scripts and styles removed, line-ending tags become breaks, entities decoded. */
object HtmlText {
    private val HEAD = Regex("(?is)<(head|script|style)\\b.*?</\\1\\s*>")
    private val COMMENTS = Regex("(?s)<!--.*?-->")
    private val BREAKS = Regex("(?i)<\\s*(?:br|/p|/div|/tr|/li|/h[1-6]|/table)\\b[^>]*>")
    private val CELL_END = Regex("(?i)</t[dh]\\s*>")
    private val TAGS = Regex("(?s)<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z]+);")
    private val SPACES = Regex("[ \\t\\u00a0\\u200b\\u200c\\u200d\\ufeff]+")
    private val BLANK_LINES = Regex("\\n\\s*\\n+")

    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "rupee" to "₹",
        "ndash" to "-", "mdash" to "-", "rarr" to "->", "hellip" to "...", "copy" to "(c)", "reg" to "(r)", "bull" to "*",
        "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\""
    )

    fun strip(html: String): String {
        var t = COMMENTS.replace(html, " ")
        t = HEAD.replace(t, " ")
        t = BREAKS.replace(t, "\n")
        t = CELL_END.replace(t, " ")
        t = TAGS.replace(t, " ")
        t = ENTITY.replace(t) { m -> entity(m.groupValues[1]) }
        t = t.replace("\r", "")
        t = SPACES.replace(t, " ").lines().joinToString("\n") { it.trim() }
        return BLANK_LINES.replace(t, "\n").trim()
    }

    private fun entity(name: String): String = when {
        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(HEX)?.let(::codePoint).orEmpty()
        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint).orEmpty()
        else -> NAMED[name.lowercase()] ?: " "
    }

    private fun codePoint(cp: Int): String = if (cp in 1..Character.MAX_CODE_POINT && cp !in SURROGATES) String(Character.toChars(cp)) else " "

    private const val HEX = 16
    private val SURROGATES = 0xD800..0xDFFF
}
