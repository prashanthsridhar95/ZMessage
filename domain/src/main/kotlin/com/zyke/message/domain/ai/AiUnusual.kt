package com.zyke.message.domain.ai

import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.UnusualSpend
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.math.RoundingMode

/** Why one payment in the review pile stands out, as the app worked it out from the person's own earlier payments. */
data class UnusualFacts(
    val payee: String,
    val amount: BigDecimal,
    val usual: BigDecimal,
    /** How many times the usual payment this is, whole. */
    val times: Int,
    /** Payments to the same payee in the history window, before this one. */
    val earlierCount: Int,
    val earlierBiggest: BigDecimal?,
    val category: String?
) {
    val lines: List<String>
        get() = buildList {
            add("Payment: ${DigestFormat.rupees(amount)} to $payee")
            add("Your usual payment: ${DigestFormat.rupees(usual)}")
            add("This is about $times times the usual")
            add(
                if (earlierCount == 0) "Earlier payments to $payee in the last ${UnusualSpend.HISTORY_DAYS} days: none"
                else "Earlier payments to $payee in the last ${UnusualSpend.HISTORY_DAYS} days: $earlierCount, the biggest ${DigestFormat.rupees(earlierBiggest ?: BigDecimal.ZERO)}"
            )
            category?.let { add("Category: $it") }
        }

    /** The app's own sentence, shown at once. */
    val plain: String
        get() = "About $times times your usual payment of ${DigestFormat.rupees(usual)}, " + if (earlierCount == 0) {
            "and your first payment to $payee in the last ${UnusualSpend.HISTORY_DAYS} days."
        } else {
            "and you paid $payee $earlierCount ${if (earlierCount == 1) "time" else "times"} before in the last ${UnusualSpend.HISTORY_DAYS} days (the biggest was ${DigestFormat.rupees(earlierBiggest ?: BigDecimal.ZERO)})."
        }

    val key: String get() = AiKeys.of("unusual", lines.joinToString("|"))
}

object AiUnusual {
    const val MAX_CHARS = 200
    private const val MAX_SENTENCES = 1
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val ALARMING = Regex("(?i)\\b(fraud\\w*|scam\\w*|stolen|steal\\w*|hack\\w*|unauthori[sz]ed|suspicious|phish\\w*|cheat\\w*|theft)\\b")

    /** Null when the payment cannot be compared (too little history, no payee, or it is not a debit the app counts). */
    fun facts(message: ClassifiedMessage, messages: List<ClassifiedMessage>, nowMs: Long): UnusualFacts? {
        val amount = Counting.spend(message)?.takeIf { it.signum() > 0 } ?: return null
        val payee = message.merchant.name.trim().takeIf { it.isNotEmpty() } ?: return null
        val since = nowMs - UnusualSpend.HISTORY_DAYS * DAY_MS
        val history = messages.mapNotNull { m ->
            val spent = Counting.spend(m)?.takeIf { it.signum() > 0 }
            if (spent == null || m.message.date < since || m.message.date > nowMs) null else m to spent
        }
        val usual = UnusualSpend.median(history.map { it.second }) ?: return null
        val times = amount.divide(usual, 0, RoundingMode.HALF_UP).toInt()
        if (times < 2) return null
        val earlier = history.filter { (m, _) -> m.id != message.id && m.message.date < message.message.date && m.merchant.name.trim().equals(payee, ignoreCase = true) }
        val category = message.category.takeIf { it != MessageCategory.OTHERS && it != MessageCategory.UPI_PAYMENT }?.displayString
        return UnusualFacts(payee, amount, usual, times, earlier.size, earlier.maxOfOrNull { it.second }, category)
    }

    /** One sentence from the model, if it passes every check and does not cry fraud. */
    fun verify(reply: String, facts: UnusualFacts): String? {
        val text = AiSentence.verify(reply, facts.lines, MAX_CHARS, MAX_SENTENCES) ?: return null
        return text.takeUnless { ALARMING.containsMatchIn(it) }
    }
}

/** "Explain unusual payments": one sentence for a payment in the review pile. */
class AiUnusualWriter(private val gateway: AiGateway, private val cache: AiCache) {
    private val phraser = AiPhraser(gateway, cache)

    fun known(facts: UnusualFacts): String? = phraser.known(facts.key) { AiUnusual.verify(it, facts) }

    suspend fun explain(facts: UnusualFacts): Phrased =
        phraser.phrase(facts.key, AiPrompts.unusual(facts.lines), TOKENS) { AiUnusual.verify(it, facts) }

    private companion object { const val TOKENS = 70 }
}
