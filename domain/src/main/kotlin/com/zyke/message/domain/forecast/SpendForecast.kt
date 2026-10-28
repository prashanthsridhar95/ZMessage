package com.zyke.message.domain.forecast

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.plan.SalaryDetector
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class CutReason {
    /** On pace to spend clearly more than the person's usual in the category. */
    ABOVE_USUAL,

    /** One of the biggest shares of what is spent, and a flexible one. */
    BIGGEST_SHARE
}

/** A category worth trimming, and what trimming it for the rest of the cycle would save. */
data class CutSuggestion(
    val category: MessageCategory,
    /** What the category adds up to over the whole cycle at the current pace. */
    val projected: BigDecimal,
    /** The category's average over recent cycles; null when there is no history. */
    val usual: BigDecimal?,
    /** What is saved over the rest of the cycle by cutting back as suggested. */
    val saving: BigDecimal,
    val reason: CutReason
)

/** Where the current cycle is heading if spending carries on as it has. */
data class SpendForecast(
    val cycleEnd: LocalDate,
    val daysLeft: Int,
    /** What the bank accounts hold today; null when no balance is known, which leaves [endBalance] unknown too. */
    val balanceNow: BigDecimal?,
    /** Spent each day so far this cycle from the bank accounts, apart from what was set aside. */
    val bankPerDay: BigDecimal,
    /** The same for cards. */
    val cardPerDay: BigDecimal,
    /** What the bank accounts will lose to everyday spending before the cycle ends. */
    val projectedBankSpend: BigDecimal,
    /** Card spending still to come this cycle, which lands on the card bill. */
    val projectedCardSpend: BigDecimal,
    /** Card bills and announced auto-debits falling due before the cycle ends. */
    val billsDue: BigDecimal,
    /** The salary expected before the cycle ends; zero when it has arrived or none is known. */
    val expectedIncome: BigDecimal,
    val endBalance: BigDecimal?,
    /** When the bank balance is expected to run out, if it does. */
    val runsOutOn: LocalDate?,
    /** How much would have to go on a credit card to cover the gap; null when the balance is enough. */
    val cardNeeded: BigDecimal?,
    val suggestions: List<CutSuggestion>
) {
    val totalSaving: BigDecimal get() = suggestions.fold(BigDecimal.ZERO) { sum, s -> sum + s.saving }
}

object SpendForecasts {
    private val SET_ASIDE = setOf(
        MessageCategory.INVESTMENTS, MessageCategory.SAVINGS, MessageCategory.INSURANCE,
        MessageCategory.CHARITY, MessageCategory.CREDIT_CARDS
    )

    /** Categories a person can choose to spend less on. Rent-like and fixed costs are left alone. */
    internal val FLEXIBLE = setOf(
        MessageCategory.ENTERTAINMENT, MessageCategory.FOOD, MessageCategory.SHOPPING, MessageCategory.TRAVEL,
        MessageCategory.UPI_PAYMENT, MessageCategory.OTHERS, MessageCategory.FUEL, MessageCategory.GROCERIES, MessageCategory.FAMILY
    )

    private const val EARLY_DAYS = 3
    private const val HISTORY_CYCLES = 3
    private val ABOVE_USUAL = BigDecimal("1.10")
    private val TRIM = BigDecimal("0.15")
    private val BIG_SHARE = BigDecimal("0.15")
    private val MIN_SAVING = BigDecimal(100)
    private const val MAX_SUGGESTIONS = 3

    private class Spend(val date: LocalDate, val amount: BigDecimal, val category: MessageCategory, val isCard: Boolean)

    fun build(
        messages: List<ClassifiedMessage>,
        balanceNow: BigDecimal?,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule
    ): SpendForecast? {
        val today = LocalDate.ofInstant(now, zone)
        val range = cycle.containing(today)
        val elapsed = (ChronoUnit.DAYS.between(range.start, today).toInt() + 1).coerceAtLeast(1)
        val remaining = (cycle.daysLeft(today) - 1).coerceAtLeast(0)
        val cycleLength = cycle.length(today)

        val spends = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (tx.isReceived || tx.isCardPayment || message.category in SET_ASIDE) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            Spend(LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone), amount, message.category, tx.isCard)
        }
        val thisCycle = spends.filter { it.date >= range.start && !it.date.isAfter(today) }
        // Without a few days of spending to go on there is nothing honest to say yet.
        if (thisCycle.isEmpty() && spends.none { it.date < range.start }) return null

        val previous = (1..HISTORY_CYCLES).map { cycle.shifted(today, -it) }.map { r -> spends.filter { it.date in r.start..r.endInclusive } }
            .filter { it.isNotEmpty() }
        val previousPerDay = previous.firstOrNull()?.let { list ->
            list.fold(BigDecimal.ZERO) { sum, s -> sum + s.amount }.divide(BigDecimal(cycleLength), 2, RoundingMode.HALF_UP)
        }

        fun perDay(list: List<Spend>, previousList: List<Spend>?): BigDecimal {
            val sofar = list.fold(BigDecimal.ZERO) { sum, s -> sum + s.amount }.divide(BigDecimal(elapsed), 2, RoundingMode.HALF_UP)
            val before = previousList?.fold(BigDecimal.ZERO) { sum, s -> sum + s.amount }?.divide(BigDecimal(cycleLength), 2, RoundingMode.HALF_UP)
            // Early in a cycle the last one is given an equal say, so one big first-day payment does not set the pace.
            return if (elapsed >= EARLY_DAYS || before == null) sofar else (sofar + before).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
        }
        val previousSpends = previous.firstOrNull()
        val bankPerDay = perDay(thisCycle.filter { !it.isCard }, previousSpends?.filter { !it.isCard })
        val cardPerDay = perDay(thisCycle.filter { it.isCard }, previousSpends?.filter { it.isCard })
        val projectedBank = bankPerDay.multiply(BigDecimal(remaining))
        val projectedCard = cardPerDay.multiply(BigDecimal(remaining))

        val bills = UpcomingBuilder.build(messages, today).filter { !it.date.isAfter(range.endInclusive) }
        val billsDue = bills.fold(BigDecimal.ZERO) { sum, b -> sum + b.amount }

        val salary = SalaryDetector.detect(messages, now, zone)
        val salaryDate = salary?.let { nextSalaryDate(it.typicalDay, today, range.endInclusive) }
        val expectedIncome = if (salary != null && salaryDate != null) salary.typical else BigDecimal.ZERO

        val end = balanceNow?.let { it + expectedIncome - projectedBank - billsDue }
        val runsOut = balanceNow?.let { runOutDate(it, bankPerDay, bills.map { b -> b.date to b.amount }, salaryDate, expectedIncome, today, range.endInclusive) }
        val cardNeeded = end?.takeIf { it.signum() < 0 }?.negate()

        return SpendForecast(
            cycleEnd = range.endInclusive, daysLeft = remaining, balanceNow = balanceNow,
            bankPerDay = bankPerDay, cardPerDay = cardPerDay,
            projectedBankSpend = projectedBank, projectedCardSpend = projectedCard,
            billsDue = billsDue, expectedIncome = expectedIncome, endBalance = end, runsOutOn = runsOut, cardNeeded = cardNeeded,
            suggestions = suggestions(thisCycle, previous, elapsed, remaining, cycleLength)
        )
    }

    private fun nextSalaryDate(day: Int, today: LocalDate, end: LocalDate): LocalDate? {
        var month = YearMonth.from(today)
        repeat(2) {
            val date = month.atDay(minOf(day, month.lengthOfMonth()))
            if (date.isAfter(today) && !date.isAfter(end)) return date
            month = month.plusMonths(1)
        }
        return null
    }

    private fun runOutDate(
        start: BigDecimal, perDay: BigDecimal, bills: List<Pair<LocalDate, BigDecimal>>,
        salaryDate: LocalDate?, salary: BigDecimal, today: LocalDate, end: LocalDate
    ): LocalDate? {
        var balance = start
        var day = today.plusDays(1)
        while (!day.isAfter(end)) {
            balance -= perDay
            bills.filter { it.first == day }.forEach { balance -= it.second }
            if (day == salaryDate) balance += salary
            if (balance.signum() < 0) return day
            day = day.plusDays(1)
        }
        return null
    }

    private fun suggestions(
        thisCycle: List<Spend>, previous: List<List<Spend>>, elapsed: Int, remaining: Int, cycleLength: Int
    ): List<CutSuggestion> {
        if (remaining == 0) return emptyList()
        val total = thisCycle.fold(BigDecimal.ZERO) { sum, s -> sum + s.amount }
        if (total.signum() == 0) return emptyList()

        return FLEXIBLE.mapNotNull { category ->
            val soFar = thisCycle.filter { it.category == category }.fold(BigDecimal.ZERO) { sum, s -> sum + s.amount }
            if (soFar.signum() == 0) return@mapNotNull null
            val perDay = soFar.divide(BigDecimal(elapsed), 2, RoundingMode.HALF_UP)
            val projected = perDay.multiply(BigDecimal(cycleLength)).setScale(0, RoundingMode.HALF_UP)
            val rest = perDay.multiply(BigDecimal(remaining))

            val history = previous.map { cycle -> cycle.filter { it.category == category }.fold(BigDecimal.ZERO) { sum, s -> sum + s.amount } }
                .filter { it.signum() > 0 }
            val usual = history.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)
                ?.divide(BigDecimal(history.size), 0, RoundingMode.HALF_UP)

            val suggestion = when {
                usual != null && projected > usual.multiply(ABOVE_USUAL) ->
                    CutSuggestion(category, projected, usual, (projected - usual).min(rest).setScale(0, RoundingMode.HALF_UP), CutReason.ABOVE_USUAL)
                soFar >= total.multiply(BIG_SHARE) ->
                    CutSuggestion(category, projected, usual, rest.multiply(TRIM).setScale(0, RoundingMode.HALF_UP), CutReason.BIGGEST_SHARE)
                else -> null
            }
            suggestion?.takeIf { it.saving >= MIN_SAVING }
        }.sortedByDescending { it.saving }.take(MAX_SUGGESTIONS)
    }
}
