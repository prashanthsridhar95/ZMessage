package com.zyke.message.domain.ai

import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One thing that pushed spending up: a category or a payee, with this month's and last month's amounts (worked out by the app). */
data class WhyRow(val label: String, val isPayee: Boolean, val current: BigDecimal, val previous: BigDecimal) {
    val delta: BigDecimal get() = current - previous
    val isNew: Boolean get() = previous.signum() <= 0

    /** "+₹1,800", or "new ₹900" for something with nothing last month. */
    val shortText: String get() = if (isNew) "new ${DigestFormat.rupees(current)}" else "+${DigestFormat.rupees(delta)}"
}

/** Why this month's spending is above last month's at the same point: what the app computed, and what the model is allowed to say about it. */
data class WhyFacts(val spent: BigDecimal, val previousSpent: BigDecimal, val rows: List<WhyRow>) {
    val delta: BigDecimal get() = spent - previousSpent

    /** The lines the model is shown; their numbers and names are the only ones its sentence may use. */
    val lines: List<String>
        get() = buildList {
            add("Spent so far: ${DigestFormat.rupees(spent)}")
            add("Same point last month: ${DigestFormat.rupees(previousSpent)}")
            add("Up by: ${DigestFormat.rupees(delta)}")
            rows.forEach { r ->
                val kind = if (r.isPayee) "Payee" else "Category"
                add(
                    if (r.isNew) "$kind new this month: ${r.label} ${DigestFormat.rupees(r.current)}"
                    else "$kind up: ${r.label} ${DigestFormat.rupees(r.current)} now, ${DigestFormat.rupees(r.previous)} before, ${DigestFormat.rupees(r.delta)} more"
                )
            }
        }

    /** The app's own words, shown at once and whenever the model is off, busy or wrong. */
    val plain: String
        get() = "Spending is ${DigestFormat.rupees(delta)} above last month at this point." +
            rows.take(PLAIN_ROWS).joinToString(separator = ", ", prefix = " Biggest reasons: ", postfix = ".") { "${it.label} (${it.shortText})" }

    val key: String get() = AiKeys.of("why", lines.joinToString("|"))

    private companion object { const val PLAIN_ROWS = 3 }
}

object AiWhy {
    const val MAX_CHARS = 220
    private const val MAX_SENTENCES = 2
    private const val MAX_PAYEES = 3
    private const val MAX_CATEGORIES = 2
    private val LESS = Regex("(?i)\\b(less|lower|below|down|fewer|under|dropped|fell|decreased)\\b")
    private val MORE = Regex("(?i)\\b(more|higher|above|up|greater|over|rose|increased|rise|risen)\\b")

    /** Null unless spending really is above last month at this point and there is a reason to name. */
    fun facts(summary: MonthlySummary, messages: List<ClassifiedMessage>, zone: ZoneId): WhyFacts? {
        val previousRange = summary.previousRange ?: return null
        if (summary.spentDelta.signum() <= 0 || summary.previousSpent.signum() <= 0) return null
        val now = payeeTotals(messages, summary.range, zone)
        val before = payeeTotals(messages, previousRange, zone)
        val payees = now.map { (name, amount) -> WhyRow(name, true, amount, before[name] ?: BigDecimal.ZERO) }
            .filter { it.delta.signum() > 0 }.sortedByDescending { it.delta }.take(MAX_PAYEES)
        val categories = summary.changes.filter { it.delta.signum() > 0 }.take(MAX_CATEGORIES)
            .map { WhyRow(it.category.displayString, false, it.current, it.previous) }
        val rows = (categories + payees)
        if (rows.isEmpty()) return null
        return WhyFacts(summary.spent, summary.previousSpent, rows)
    }

    private fun payeeTotals(messages: List<ClassifiedMessage>, range: DateRange, zone: ZoneId): Map<String, BigDecimal> =
        messages.mapNotNull { m ->
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(m.message.date), zone)
            val name = m.merchant.name.trim()
            val amount = Counting.spend(m)
            if (day !in range || name.isEmpty() || amount == null) null else name to amount
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.fold(BigDecimal.ZERO, BigDecimal::add) }

    /** The model's sentence if it passes every check, else null (the plain version stays). */
    fun verify(reply: String, facts: WhyFacts): String? {
        val text = AiSentence.verify(reply, facts.lines, MAX_CHARS, MAX_SENTENCES) ?: return null
        // The spending went up: a sentence that says it went down is wrong whatever its figures are.
        if (LESS.containsMatchIn(text) && !MORE.containsMatchIn(text)) return null
        return text
    }
}

/** "Why did spending go up?": phrases [WhyFacts] on request. */
class AiWhyWriter(private val gateway: AiGateway, private val cache: AiCache) {
    private val phraser = AiPhraser(gateway, cache)

    fun known(facts: WhyFacts): String? = phraser.known(facts.key) { AiWhy.verify(it, facts) }

    suspend fun explain(facts: WhyFacts): Phrased =
        phraser.phrase(facts.key, AiPrompts.whyUp(facts.lines), TOKENS) { AiWhy.verify(it, facts) }

    private companion object { const val TOKENS = 110 }
}
