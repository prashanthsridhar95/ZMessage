package com.zyke.message.domain.logo

import com.zyke.message.domain.categories.PayeeKey
import com.zyke.message.domain.nickname.PictureSearchQuery

/** One brand and where its logo is on the web: [key] is [LogoList.keyOf] of the brand's name, [url] a clean https address ([LogoList.cleanUrl]). */
data class LogoEntry(val key: String, val url: String)

/**
 * The logo list: which web address a brand's logo is loaded from. It is a list of brand names and web addresses and nothing else,
 * so it can be handed on (the person's own list, exported from Settings) and bundled with the app (`assets/known_logos.json`)
 * without any private data in it.
 *
 * - A **key** is the payee key of the brand's name ([PayeeKey]: lower case, no punctuation), and only when it is a plain
 *   name: never a UPI id, never a word that looks like a number, and never longer than a short name. So "Taco Bell" and
 *   "TACO BELL." are one key, and "9876543210@ybl" and "Acct 123456" have none.
 * - A **web address** is https only, names a public host (no IP address, port, user name, fragment, local name) and is short.
 * - The file is `{"version":1,"logos":[{"key":"taco bell","url":"https://..."}]}`.
 * - **Precedence** ([merge]): when two lists name the same key, the second list wins. On the phone the person's own picture
 *   for a payee always wins over anything in the list (the list is only asked when there is no picture of their own).
 */
object LogoList {
    const val VERSION = 1
    const val MAX_ENTRIES = 5_000
    const val MAX_URL_LENGTH = 500
    /** The largest file text [parse] looks at. */
    const val MAX_TEXT = 1_000_000

    private val HOST = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".corp", ".intranet")

    /** The brand key for [name], or null when [name] is not a plain name (a UPI id, a number, nothing but punctuation). */
    fun keyOf(name: String): String? {
        if (PayeeKey.isVpa(name)) return null
        val key = PayeeKey.of(name) ?: return null
        // The same cleaning the picture search uses: a key it would change had something private-looking in it.
        return key.takeIf { PictureSearchQuery.clean(it) == it }
    }

    /** [raw] as a web address the list accepts (https, public host, no fragment), host in lower case; null when it is not one. */
    fun cleanUrl(raw: String): String? {
        val url = raw.trim()
        if (url.length > MAX_URL_LENGTH || url.any { it.isWhitespace() || it.isISOControl() || it.code > MAX_ASCII }) return null
        if (!url.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = url.substring(SCHEME.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || '#' in rest) return null
        val host = rest.substring(0, slash).lowercase()
        if (!isPublicHost(host)) return null
        return SCHEME + host + rest.substring(slash)
    }

    /** The host of an address that [cleanUrl] accepts, else null. */
    fun hostOf(url: String): String? = cleanUrl(url)?.substring(SCHEME.length)?.substringBefore('/')

    private fun isPublicHost(host: String): Boolean {
        if (host.length > MAX_HOST || !HOST.matches(host)) return false
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return false
        // An address such as 192.168.0.1 has no letter in its last label; a real host's last label does.
        return host.substringAfterLast('.').any { it.isLetter() }
    }

    /** Both lists as one, by key; where a key is in both, the one in [overrides] wins. Sorted by key; entries that are not clean are dropped. */
    fun merge(base: Collection<LogoEntry>, overrides: Collection<LogoEntry>): List<LogoEntry> {
        val byKey = java.util.TreeMap<String, String>()
        for (entry in base + overrides) {
            if (keyOf(entry.key) != entry.key) continue
            val url = cleanUrl(entry.url) ?: continue
            byKey[entry.key] = url
        }
        return byKey.entries.take(MAX_ENTRIES).map { LogoEntry(it.key, it.value) }
    }

    /** Key to address, for looking brands up. */
    fun table(entries: Collection<LogoEntry>): Map<String, String> = entries.associate { it.key to it.url }

    /** The first of [names] (not turned off by [isHidden]) that has an entry in [table], with its address; null when none. */
    fun resolve(names: List<String>, table: Map<String, String>, isHidden: (String) -> Boolean): Pair<String, String>? {
        for (name in names) {
            if (isHidden(name)) continue
            val url = keyOf(name)?.let(table::get) ?: continue
            return name to url
        }
        return null
    }

    /** The hosts a list would ever be asked for a picture: what the privacy page says the bundled list may contact. */
    fun hosts(entries: Collection<LogoEntry>): Set<String> = entries.mapNotNull { hostOf(it.url) }.toSortedSet()

    /** The list as the file text ([VERSION] 1), sorted by key, one entry a line. Entries that are not clean are left out, so what is shared is only names and addresses. */
    fun toJson(entries: Collection<LogoEntry>): String {
        val clean = merge(emptyList(), entries)
        return buildString {
            append("{\n  \"version\": ").append(VERSION).append(",\n  \"logos\": [")
            clean.forEachIndexed { index, entry ->
                append(if (index == 0) "\n    " else ",\n    ")
                append("{\"key\": ").append(quote(entry.key)).append(", \"url\": ").append(quote(entry.url)).append('}')
            }
            if (clean.isNotEmpty()) append("\n  ")
            append("]\n}\n")
        }
    }

    /** What [parse] found: the entries that are clean, and how many were left out because they were not. */
    data class Parsed(val entries: List<LogoEntry>, val rejected: Int)

    /**
     * The entries of a file made by [toJson] (or written by hand to the same format), or null when [text] is not that format
     * (not JSON, too long, another version). An entry with an unclean key or address is counted in `rejected` and left out;
     * a key given twice keeps the later one.
     */
    fun parse(text: String): Parsed? {
        if (text.length > MAX_TEXT) return null
        val root = JsonReader(text).readDocument() as? Map<*, *> ?: return null
        if ((root["version"] as? Long) != VERSION.toLong()) return null
        val logos = root["logos"] as? List<*> ?: return null
        var rejected = 0
        val found = ArrayList<LogoEntry>()
        for ((index, item) in logos.withIndex()) {
            val map = item as? Map<*, *>
            val key = map?.get("key") as? String
            val url = (map?.get("url") as? String)?.let(::cleanUrl)
            if (index >= MAX_ENTRIES || key == null || keyOf(key) != key || url == null) rejected++ else found += LogoEntry(key, url)
        }
        return Parsed(merge(emptyList(), found), rejected)
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < SPACE -> append("\\u").append(c.code.toString(HEX).padStart(HEX_DIGITS, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    private const val SCHEME = "https://"
    private const val MAX_HOST = 253
    private const val MAX_ASCII = 126
    private const val SPACE = 0x20
    private const val HEX = 16
    private const val HEX_DIGITS = 4
}

/** A strict reader for the small JSON the list uses: objects, arrays, strings, whole numbers, true, false and null; nesting at most 8 deep. */
private class JsonReader(private val text: String) {
    private var at = 0

    /** The one value in the text, or null when the text is not exactly one JSON value. Numbers come back as Long (a fraction or exponent is not valid here). */
    fun readDocument(): Any? {
        val value = runCatching { value(0) }.getOrNull()
        skipSpace()
        return if (at == text.length) value else null
    }

    private fun skipSpace() {
        while (at < text.length && text[at].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) at++
    }

    private fun fail(): Nothing = throw IllegalArgumentException("bad json at $at")

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) fail()
        skipSpace()
        if (at >= text.length) fail()
        return when (val c = text[at]) {
            '{' -> obj(depth)
            '[' -> array(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else fail()
        }
    }

    private fun literal(word: String, result: Any?): Any? {
        if (!text.startsWith(word, at)) fail()
        at += word.length
        return result
    }

    private fun number(): Long {
        val start = at
        if (text[at] == '-') at++
        while (at < text.length && text[at].isDigit()) at++
        if (at - start > MAX_DIGITS || at == start || (at < text.length && text[at] in ".eE")) fail()
        return text.substring(start, at).toLongOrNull() ?: fail()
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (true) {
            if (at >= text.length) fail()
            val c = text[at++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> out.append(escape())
                c.code < SPACE -> fail()
                else -> out.append(c)
            }
        }
    }

    private fun escape(): Char {
        if (at >= text.length) fail()
        return when (val e = text[at++]) {
            '"', '\\', '/' -> e
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (at + HEX_DIGITS > text.length) fail()
                text.substring(at, at + HEX_DIGITS).toIntOrNull(HEX)?.toChar()?.also { at += HEX_DIGITS } ?: fail()
            }
            else -> fail()
        }
    }

    private fun array(depth: Int): List<Any?> {
        at++
        val items = ArrayList<Any?>()
        skipSpace()
        if (at < text.length && text[at] == ']') { at++; return items }
        while (true) {
            items += value(depth + 1)
            skipSpace()
            if (at >= text.length) fail()
            when (text[at++]) {
                ',' -> Unit
                ']' -> return items
                else -> fail()
            }
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        at++
        val map = LinkedHashMap<String, Any?>()
        skipSpace()
        if (at < text.length && text[at] == '}') { at++; return map }
        while (true) {
            skipSpace()
            if (at >= text.length || text[at] != '"') fail()
            val key = string()
            skipSpace()
            if (at >= text.length || text[at++] != ':') fail()
            map[key] = value(depth + 1)
            skipSpace()
            if (at >= text.length) fail()
            when (text[at++]) {
                ',' -> Unit
                '}' -> return map
                else -> fail()
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_DIGITS = 18
        const val SPACE = 0x20
        const val HEX = 16
        const val HEX_DIGITS = 4
    }
}
