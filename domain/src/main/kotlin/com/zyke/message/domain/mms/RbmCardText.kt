package com.zyke.message.domain.mms

/**
 * Pulls the readable text out of a rich card's JSON payload, defensively. UNVERIFIED: how Google Messages stores an RBM card in the
 * MMS provider (which content type, which column) was not seen on a device. This accepts any JSON, walks it in document order with a
 * bounded depth, and collects the string values of keys that usually hold what a person reads (title, description, text, message,
 * subtitle). Anything malformed yields nothing, never an exception.
 */
object RbmCardText {
    /** Most text kept from one payload. */
    const val MAX_CHARS = 4 * 1024
    private const val MAX_DEPTH = 12
    private const val MAX_INPUT = 64 * 1024
    private val keys = setOf("title", "description", "text", "message", "subtitle")

    fun strings(json: String): List<String> {
        val out = ArrayList<String>()
        var total = 0
        try {
            val root = Parser(json.take(MAX_INPUT)).parseValue(0)
            fun walk(node: Any?, depth: Int) {
                if (depth > MAX_DEPTH || total >= MAX_CHARS) return
                when (node) {
                    is Map<*, *> -> for ((k, v) in node) {
                        if (v is String && (k as? String)?.substringBefore('#')?.lowercase() in keys) {
                            val s = v.trim()
                            if (s.isNotEmpty() && total < MAX_CHARS && out.lastOrNull() != s) {
                                val part = s.take(MAX_CHARS - total)
                                out += part
                                total += part.length
                            }
                        } else {
                            walk(v, depth + 1)
                        }
                    }
                    is List<*> -> node.forEach { walk(it, depth + 1) }
                }
            }
            walk(root, 0)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Malformed or too deep: whatever was collected before stays.
        }
        return out
    }

    /** A small JSON reader: objects keep their order, numbers and literals are read as null. Throws on malformed input. */
    private class Parser(private val s: String) {
        private var i = 0

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(): Nothing = throw IllegalArgumentException("json")

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail()
            ws()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                else -> literal()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                map[uniqueKey(map, k)] = parseValue(depth + 1)
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return map }
                fail()
            }
        }

        /** The same key twice keeps both values in order. */
        private fun uniqueKey(map: Map<String, Any?>, k: String): String {
            if (k !in map) return k
            var n = 1
            while ("$k#$n" in map) n++
            return "$k#$n"
        }

        private fun arr(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list += parseValue(depth + 1)
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return list }
                fail()
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            fail()
        }

        private fun literal(): Any? {
            val start = i
            while (i < s.length && s[i] !in ",]} \t\r\n") i++
            if (i == start) fail()
            return null
        }
    }
}
