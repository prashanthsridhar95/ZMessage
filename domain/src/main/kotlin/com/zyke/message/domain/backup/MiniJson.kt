package com.zyke.message.domain.backup

/**
 * Just enough JSON to read a backup envelope in pure Kotlin: objects (Map), arrays (List), strings, integers (Long),
 * decimals (Double), booleans and null. Throws [IllegalArgumentException] on anything malformed.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpace()
        require(p.atEnd()) { "trailing characters" }
        return value
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipSpace()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> container { obj() }
                '[' -> container { arr() }
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c'")
            }
        }

        private fun <T> container(block: () -> T): T {
            require(++depth <= MAX_DEPTH) { "too deep" }
            return block().also { depth-- }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipSpace()
                require(peek() == '"') { "expected key" }
                val k = str()
                skipSpace()
                require(peek() == ':') { "expected ':'" }
                i++
                out[k] = value()
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char {
            require(i < s.length) { "unexpected end" }
            return s[i]
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
        }
    }

    private const val MAX_DEPTH = 32
}
