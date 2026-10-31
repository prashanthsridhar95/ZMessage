package com.zyke.message.domain.teach

import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.SmsMessage

/** Turns the tokens a person tapped in one message into a [LearnedTemplate]. Pure and strict: when unsure it returns null. */
object TemplateLearner {
    const val MAX_BODY = 4096
    const val MAX_PAYEE = 60
    private const val MIN_LITERAL_WORDS = 2

    data class Token(val text: String, val start: Int, val end: Int) {
        val isNumber: Boolean get() = text[0].isDigit()
    }

    private val TOKEN = Regex("""\d+(?:,\d+)*(?:\.\d+)?|\p{L}+(?:['&-]\p{L}+)*""")
    private const val NUMBER = """[\d,]+(?:\.\d+)?"""
    private val SPECIAL = "\\.[]{}()*+-?^$|/"

    /** Words and numbers of the (trimmed) message, in order; what the person taps. */
    fun tokenize(body: String): List<Token> {
        val text = body.trim()
        if (text.length > MAX_BODY) return emptyList()
        return TOKEN.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()
    }

    fun isPositive(text: String): Boolean = LearnedTemplates.decimal(text)?.let { it.signum() > 0 } == true

    fun learn(message: SmsMessage, picks: TeachPicks): LearnedTemplate? {
        val body = message.body.trim()
        val tokens = tokenize(body)
        val amountToken = tokens.getOrNull(picks.amount)?.takeIf { it.isNumber && isPositive(it.text) } ?: return null
        val accountToken = picks.account?.let { index -> tokens.getOrNull(index)?.takeIf { t -> t.text.all(Char::isDigit) } ?: return null }
        val payeeRange = picks.payee?.also { if (it.isEmpty() || it.first < 0 || it.last >= tokens.size) return null }

        // Every pick owns a span of the text; no two may overlap.
        val spans = ArrayList<Triple<Int, Int, Char>>() // start, end, kind
        spans += Triple(amountToken.start, amountToken.end, 'A')
        accountToken?.let { spans += Triple(it.start, it.end, 'C') }
        payeeRange?.let { spans += Triple(tokens[it.first].start, tokens[it.last].end, 'P') }
        spans.sortBy { it.first }
        for (i in 1 until spans.size) if (spans[i].first < spans[i - 1].second) return null

        val sb = StringBuilder("\\A")
        var pos = 0
        var literalWords = 0
        val order = ArrayList<Char>()
        for (span in spans) {
            literalWords += append(sb, body, tokens, pos, span.first)
            when (span.third) {
                'A' -> sb.append("($NUMBER)")
                'C' -> sb.append("(\\d+)")
                else -> sb.append("([^\\r\\n]+?)")
            }
            order += span.third
            pos = span.second
        }
        literalWords += append(sb, body, tokens, pos, body.length)
        sb.append("\\z")
        if (literalWords < MIN_LITERAL_WORDS) return null

        val pattern = sb.toString()
        val groups = TemplateGroups(
            amount = order.indexOf('A') + 1,
            payee = order.indexOf('P').takeIf { it >= 0 }?.plus(1),
            account = order.indexOf('C').takeIf { it >= 0 }?.plus(1)
        )
        val template = LearnedTemplate(
            id = idOf(SenderKey.of(message.sender), pattern, picks.isReceived),
            senderKey = SenderKey.of(message.sender),
            pattern = pattern,
            groups = groups,
            isReceived = picks.isReceived,
            category = picks.category
        )
        // It must read back exactly what was picked from the very message it came from.
        val read = template.apply(message) ?: return null
        if (LearnedTemplates.decimal(read.amount) != LearnedTemplates.decimal(amountToken.text)) return null
        if (accountToken != null && read.accountNumber != accountToken.text.takeLast(4)) return null
        if (payeeRange != null && read.payee != body.substring(tokens[payeeRange.first].start, tokens[payeeRange.last].end).trim().replace(Regex("\\s+"), " ")) return null
        return template
    }

    /** Appends body[from, to) as pattern text; returns how many literal words it kept. */
    private fun append(sb: StringBuilder, body: String, tokens: List<Token>, from: Int, to: Int): Int {
        if (from >= to) return 0
        var words = 0
        var i = from
        while (i < to) {
            val c = body[i]
            val token = tokens.firstOrNull { it.start == i && it.end <= to }
            when {
                token != null && token.isNumber -> { sb.append(NUMBER); i = token.end }
                token != null -> { token.text.forEach { sb.append(esc(it)) }; words++; i = token.end }
                c.isWhitespace() -> {
                    while (i < to && body[i].isWhitespace()) i++
                    sb.append("\\s+")
                }
                else -> { sb.append(esc(c)); i++ }
            }
        }
        return words
    }

    private fun esc(c: Char): String = if (c in SPECIAL) "\\$c" else c.toString()

    private fun idOf(sender: String, pattern: String, received: Boolean): String =
        "%08x".format((sender + "|" + pattern + "|" + received).hashCode())
}
