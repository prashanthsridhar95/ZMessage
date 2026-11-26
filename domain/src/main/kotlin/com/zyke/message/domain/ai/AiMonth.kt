package com.zyke.message.domain.ai

import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The figures behind "this month in a sentence", all worked out by the app. The model never sees a figure it has to compute. */
data class MonthFacts(
    val spent: BigDecimal,
    val previousSpent: BigDecimal,
    /** Whole percent against last month at this point; null when last month had nothing. */
    val percent: Int?,
    val topCategory: String?,
    val topCategoryAmount: BigDecimal?,
    val biggestName: String?,
    val biggestAmount: BigDecimal?
) {
    val isUp: Boolean get() = spent > previousSpent

    /** The lines the model is shown; their numbers are the only ones its sentence may use. */
    val lines: List<String>
        get() = buildList {
            add("Spent so far: ${money(spent)}")
            if (previousSpent.signum() > 0) add("Last month at this point: ${money(previousSpent)}")
            percent?.let { add("Change: ${kotlin.math.abs(it)}% " + if (isUp) "more" else "less") }
            if (topCategory != null && topCategoryAmount != null) add("Most on: $topCategory (${money(topCategoryAmount)})")
            if (biggestName != null && biggestAmount != null) add("Biggest payment: ${money(biggestAmount)} to $biggestName")
        }

    /** The sentence the app writes itself: shown at once, and whenever the model is off, busy or wrong. */
    val plain: String
        get() = buildString {
            append("You have spent ${money(spent)} so far")
            percent?.let { append(", ${kotlin.math.abs(it)}% " + (if (isUp) "more" else "less") + " than last month at this point") }
            topCategory?.let { append(", mostly on $it") }
            append('.')
            if (biggestName != null && biggestAmount != null) append(" Your biggest payment was ${money(biggestAmount)} to $biggestName.")
        }

    /** The cache key: the same figures give the same sentence. */
    val key: String get() = AiKeys.of("month", lines.joinToString("|"))

    private fun money(v: BigDecimal) = DigestFormat.rupees(v)
}

object AiMonth {
    private const val MAX_CHARS = 240
    private const val MAX_SENTENCES = 2
    private val SYMBOLS = 0x2190..0x2BFF
    private val LESS = Regex("(?i)\\b(less|lower|below|down|fewer|under)\\b")
    private val MORE = Regex("(?i)\\b(more|higher|above|up|greater|over)\\b")

    /** Null while there is nothing spent to talk about. */
    fun facts(summary: MonthlySummary, messages: List<ClassifiedMessage>, zone: ZoneId): MonthFacts? {
        if (summary.spent.signum() <= 0) return null
        val inRange = messages.filter { LocalDate.ofInstant(Instant.ofEpochMilli(it.message.date), zone) in summary.range }
        val spends = inRange.mapNotNull { m -> Counting.spend(m)?.let { m to it } }
        val byCategory = spends.groupBy { it.first.category }.mapValues { (_, v) -> v.sumOf { it.second } }
            .filterKeys { it != com.zyke.message.domain.model.MessageCategory.OTHERS && it != com.zyke.message.domain.model.MessageCategory.UPI_PAYMENT }
        val top = byCategory.maxByOrNull { it.value }
        val biggest = spends.maxByOrNull { it.second }
        return MonthFacts(
            spent = summary.spent, previousSpent = summary.previousSpent, percent = summary.spentDeltaPercent,
            topCategory = top?.key?.displayString, topCategoryAmount = top?.value,
            biggestName = biggest?.first?.merchant?.name?.takeIf { it.isNotBlank() }, biggestAmount = biggest?.second
        )
    }

    /** The model's sentence if it passes every check, else null (and the caller shows [MonthFacts.plain]). */
    fun verify(reply: String, facts: MonthFacts): String? {
        val text = reply.trim().replace(Regex("\\s+"), " ").trim('"', '“', '”')
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        if (text.any { it.isSurrogate() || it.code in SYMBOLS } || text.any { it == '*' || it == '#' || it == '<' || it == '>' }) return null
        if (Regex("[.!?](\\s|$)").findAll(text).count() > MAX_SENTENCES) return null
        if (!NumberGuard.onlyAllowed(text, facts.lines)) return null
        if (facts.percent != null) {
            val saysLess = LESS.containsMatchIn(text)
            val saysMore = MORE.containsMatchIn(text)
            if (facts.isUp && saysLess && !saysMore) return null
            if (!facts.isUp && saysMore && !saysLess) return null
        }
        return text
    }
}
