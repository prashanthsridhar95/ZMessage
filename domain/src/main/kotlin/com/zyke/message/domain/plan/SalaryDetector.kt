package com.zyke.message.domain.plan

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A salary the messages show arriving month after month. */
data class SalaryPattern(
    /** Who pays it, as the messages name them. */
    val payer: String,
    /** What to expect: the middle of the last few months, since the amount moves a little each month. */
    val typical: BigDecimal,
    val low: BigDecimal,
    val high: BigDecimal,
    /** The day of the month it usually lands. */
    val typicalDay: Int,
    /** How many of the recent months it was seen in. */
    val monthsSeen: Int,
    /** This month's credit from the same payer, once it has arrived. */
    val thisMonth: BigDecimal?
)

/**
 * Finds the salary in the messages: a large credit from the same payer in several recent months, at about the same
 * time each month and about the same size. A one-off credit does not count, and neither does a month's bonus that
 * is far off the rest. The amount is allowed to differ a little from month to month.
 */
object SalaryDetector {
    private val MIN_AMOUNT = BigDecimal(10_000)
    private const val MONTHS_LOOKED_AT = 6
    private const val MONTHS_AVERAGED = 3
    private const val MIN_MONTHS = 2
    private val SIZE_TOLERANCE = BigDecimal("0.40")
    private val SALARY_WORDS = Regex("(?i)\\b(?:salary|sal credit|payroll|wages)\\b")

    private class Credit(val month: YearMonth, val day: Int, val amount: BigDecimal, val payer: String, val salaryWord: Boolean)

    fun detect(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): SalaryPattern? {
        val today = LocalDate.ofInstant(now, zone)
        val thisMonth = YearMonth.from(today)
        val earliest = thisMonth.minusMonths(MONTHS_LOOKED_AT - 1L)

        val credits = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (!tx.isReceived || tx.isCardPayment) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it >= MIN_AMOUNT } ?: return@mapNotNull null
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            val month = YearMonth.from(date)
            if (month < earliest || date.isAfter(today)) return@mapNotNull null
            val salaryWord = message.category == MessageCategory.SALARY || SALARY_WORDS.containsMatchIn(message.message.body)
            Credit(month, date.dayOfMonth, amount, if (salaryWord) SALARY_KEY else payerKey(tx.payee), salaryWord)
        }.filter { it.payer.isNotEmpty() }

        val patterns = credits.groupBy { it.payer }.mapNotNull { (payer, group) -> pattern(payer, group, thisMonth) }
        // A credit that says "salary" wins; then the one seen in most months; then the larger.
        return patterns.maxWithOrNull(
            compareBy<SalaryPattern> { it.payer == SALARY_KEY }.thenBy { it.monthsSeen }.thenBy { it.typical }
        )
    }

    private fun pattern(payer: String, group: List<Credit>, thisMonth: YearMonth): SalaryPattern? {
        val middle = median(group.map { it.amount })
        // Keep the credits close to the usual size, so a bonus or a refund from the same payer does not skew it.
        val steady = group.filter { (it.amount - middle).abs() <= middle.multiply(SIZE_TOLERANCE) }
        val perMonth = steady.groupBy { it.month }.mapValues { (_, credits) -> credits.maxBy { it.amount } }
        if (perMonth.size < MIN_MONTHS) return null

        val recent = perMonth.entries.sortedByDescending { it.key }.take(MONTHS_AVERAGED).map { it.value.amount }
        val earlierMonths = perMonth.filterKeys { it != thisMonth }
        val reference = if (earlierMonths.isNotEmpty()) {
            earlierMonths.entries.sortedByDescending { it.key }.take(MONTHS_AVERAGED).map { it.value.amount }
        } else {
            recent
        }

        return SalaryPattern(
            payer = payer,
            typical = median(reference).setScale(0, RoundingMode.HALF_UP),
            low = reference.min(),
            high = reference.max(),
            typicalDay = medianDay(perMonth.values.map { it.day }),
            monthsSeen = perMonth.size,
            thisMonth = perMonth[thisMonth]?.amount
        )
    }

    private fun median(values: List<BigDecimal>): BigDecimal {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
    }

    private fun medianDay(days: List<Int>): Int = days.sorted()[days.size / 2]

    /** Same payer however the bank spells the reference: lower case, with digits and punctuation dropped. */
    private fun payerKey(payee: String): String =
        payee.lowercase().replace(Regex("[^a-z@. ]+"), " ").replace(Regex("\\s+"), " ").trim()

    const val SALARY_KEY = "salary"
}
