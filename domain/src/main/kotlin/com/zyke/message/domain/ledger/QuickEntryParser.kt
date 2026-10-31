package com.zyke.message.domain.ledger

import java.math.BigDecimal

/** What a line of shared or dictated text says about a hand-made entry; the person still checks it before saving. */
data class QuickEntryDraft(val amount: BigDecimal, val title: String, val kind: ManualKind)

/**
 * Reads text such as "200 for auto", "₹1,250 dinner with Ravi", "got salary 45k" or "paid Rs. 80.50 for tea".
 * Pure and offline; the text is read once and never kept.
 */
object QuickEntryParser {
    private const val MAX_LENGTH = 500
    private const val MAX_TITLE = 60

    private val AMOUNT = Regex(
        """(?<![\p{L}\d.,])(₹|rs\.?|inr)?\s*(\d[\d,]*(?:\.\d+)?|\.\d+)\s*(k\b)?(?:\s*(rs\b\.?|rupees?\b|inr\b|/-))?""",
        RegexOption.IGNORE_CASE
    )
    private val INCOME = Regex("""\b(received|receive|got|salary|credited|credit|refund(?:ed)?|earned|income|bonus|cashback|reimbursed|reimbursement)\b""", RegexOption.IGNORE_CASE)
    private val FILLER = setOf(
        "for", "on", "to", "at", "paid", "pay", "spent", "spend", "got", "received", "receive", "from", "towards",
        "of", "by", "via", "rs", "inr", "rupees", "rupee", "in", "a", "the", "my", "was", "is"
    )

    fun parse(text: String): QuickEntryDraft? {
        val line = text.trim().take(MAX_LENGTH).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && AMOUNT.containsMatchIn(it) }
            ?: return null
        val candidates = AMOUNT.findAll(line).filter { it.groupValues[2].any(Char::isDigit) }.toList()
        // A number marked as money wins over a bare one ("room 12, ₹300").
        val match = candidates.firstOrNull { it.groupValues[1].isNotEmpty() || it.groupValues[3].isNotEmpty() || it.groupValues[4].isNotEmpty() }
            ?: candidates.firstOrNull() ?: return null
        var amount = match.groupValues[2].replace(",", "").toBigDecimalOrNull() ?: return null
        if (match.groupValues[3].isNotEmpty()) amount = amount.multiply(BigDecimal(1000))
        if (amount.signum() <= 0 || amount.precision() - amount.scale() > 12) return null
        amount = amount.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

        val kind = if (INCOME.containsMatchIn(line)) ManualKind.INCOME else ManualKind.EXPENSE
        val rest = line.removeRange(match.range).replace(Regex("""[\s,;:|\-]+"""), " ").trim()
        val words = rest.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase().trim('.', ',') in FILLER) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase().trim('.', ',') in FILLER) words.removeAt(words.lastIndex)
        var title = words.joinToString(" ").trim('.', ',', ' ')
        if (title.length > MAX_TITLE) title = title.take(MAX_TITLE).trimEnd()
        if (title.isEmpty()) title = if (kind == ManualKind.INCOME) "Cash received" else "Cash spend"
        return QuickEntryDraft(amount, title.replaceFirstChar { it.uppercase() }, kind)
    }
}
