package com.zyke.message.domain.ai

/**
 * A small, strict JSON reader for what a language model says. Models wrap answers in code fences or add a sentence before
 * them, so [firstObject] finds the first balanced `{...}`; the object itself is then parsed strictly: no comments, no
 * trailing commas, no single quotes, no text after it. Values are text, numbers, true, false, null, or a list of those.
 * Anything else, or anything too big or too deep, is null: a reply that is not valid is never half-used.
 */
object MiniJson {
    const val MAX_CHARS = 4_000
    private const val MAX_DEPTH = 3

    /** A parsed object: text and numbers are kept as written. */
    class Obj(val values: Map<String, Any?>) {
        fun text(key: String): String? = (values[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        /** A number or numeric text, as the digits written. */
        fun number(key: String): String? = when (val v = values[key]) {
            is Num -> v.raw
            is String -> v.trim().takeIf { it.isNotEmpty() }
            else -> null
        }

        fun has(key: String): Boolean = key in values
        fun isNull(key: String): Boolean = key in values && values[key] == null
    }

    class Num(val raw: String)

    /** The first object in [reply], parsed strictly; null when there is none or it is not valid. */
    fun firstObject(reply: String): Obj? {
        if (reply.length > MAX_CHARS * 2) return null
        val start = reply.indexOf('{')
        if (start < 0) return null
        val end = balancedEnd(reply, start)
        if (end < 0 || end - start > MAX_CHARS) return null
        val parser = Parser(reply.substring(start, end + 1))
        return parser.parseTop()
    }

    private fun balancedEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                inString -> if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseTop(): Obj? = try {
            val value = value(0) as? Obj
            skip()
            if (i == s.length) value else null
        } catch (@Suppress("SwallowedException") e: RuntimeException) {
            null
        }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw Bad
            skip()
            if (i >= s.length) throw Bad
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> list(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw Bad
            }
        }

        private fun word(text: String, result: Any?): Any? {
            if (!s.startsWith(text, i)) throw Bad
            i += text.length
            return result
        }

        private fun number(): Num {
            val m = NUMBER.matchAt(s, i) ?: throw Bad
            i += m.value.length
            return Num(m.value)
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(escape())
                    else -> if (c < ' ') throw Bad else sb.append(c)
                }
            }
            throw Bad
        }

        private fun escape(): Char {
            if (i >= s.length) throw Bad
            return when (val e = s[i++]) {
                '"', '\\', '/' -> e
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000C'
                'u' -> {
                    if (i + 4 > s.length) throw Bad
                    val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw Bad
                    i += 4
                    code.toChar()
                }
                else -> throw Bad
            }
        }

        private fun list(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                skip()
                if (i >= s.length) throw Bad
                when (s[i++]) {
                    ',' -> Unit
                    ']' -> return out
                    else -> throw Bad
                }
            }
        }

        private fun obj(depth: Int): Obj {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return Obj(out) }
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') throw Bad
                val key = string()
                skip()
                if (i >= s.length || s[i++] != ':') throw Bad
                out[key] = value(depth + 1)
                skip()
                if (i >= s.length) throw Bad
                when (s[i++]) {
                    ',' -> Unit
                    '}' -> return Obj(out)
                    else -> throw Bad
                }
            }
        }

        private companion object {
            val NUMBER = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
        }
    }

    private object Bad : RuntimeException(null, null, false, false)
}
