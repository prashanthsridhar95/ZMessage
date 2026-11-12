package com.zyke.message.domain.cards

import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One row of the category breakdown. [category] is null for the "Other" row that gathers the small ones. */
data class CategoryShare(val category: MessageCategory?, val amount: BigDecimal, val percent: Int)

/** Where one card stands in its current cycle: what was spent, on what, and when the cycle resets. Pure, so it is tested. */
data class CardSpendView(
    val spent: BigDecimal,
    /** Money that came in over the same days (income on a bank account; always zero for a card). */
    val received: BigDecimal,
    /** First and last day of the cycle; [cycleKnown] is false when the card has no statement day (calendar months, no reset line). */
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val cycleKnown: Boolean,
    val shares: List<CategoryShare>,
    /** Newest first. */
    val recent: List<ClassifiedMessage>
)

object CardSpend {
    const val TOP_CATEGORIES = 4
    const val RECENT_SHOWN = 5

    /**
     * What a card spent in the cycle that contains [today], from the same counting rules as every other total ([Counting]): card
     * payments, set-aside and repeated gateway texts are not counted, a card refund is taken off. A message belongs to the card when
     * it is a card transaction (or, with [isCard] false, a bank account one) whose account ends in [last4].
     */
    fun view(messages: List<ClassifiedMessage>, last4: String, statementDay: Int?, today: LocalDate, zone: ZoneId, isCard: Boolean = true): CardSpendView {
        val end = CardCycleBuilder.cycleEnd(today, statementDay)
        val start = CardCycleBuilder.cycleStart(end, statementDay)
        val mine = messages.filter { m ->
            val tx = m.content as? MessageContent.Transaction ?: return@filter false
            tx.isCard == isCard && tx.accountNumber.filter { it.isDigit() }.takeLast(4) == last4
        }
        val inCycle = mine.filter { m ->
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(m.message.date), zone)
            !day.isBefore(start) && !day.isAfter(end) && !day.isAfter(today)
        }
        val entries = ArrayList<Pair<MessageCategory, BigDecimal>>()
        var received = BigDecimal.ZERO
        for (m in inCycle) when (val c = Counting.of(m)) {
            is Counted.Spend -> entries += m.category to c.amount
            is Counted.Refund -> entries += m.category to c.amount.negate()
            is Counted.Income -> if (!isCard) received += c.amount
            else -> Unit
        }
        val spent = entries.fold(BigDecimal.ZERO) { sum, e -> sum + e.second }
        val recent = mine.filter { val c = Counting.of(it); c is Counted.Spend || (!isCard && c is Counted.Income) }.sortedByDescending { it.message.date }.take(RECENT_SHOWN)
        return CardSpendView(spent.max(BigDecimal.ZERO), received, start, end, statementDay != null, breakdown(entries), recent)
    }

    /**
     * The top [top] categories by amount, the rest gathered as "Other" (null category), with whole-number percentages that add up to
     * exactly 100 (largest remainder). Zero and negative totals are left out; nothing spent gives an empty list.
     */
    fun breakdown(entries: List<Pair<MessageCategory, BigDecimal>>, top: Int = TOP_CATEGORIES): List<CategoryShare> {
        val totals = entries.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.fold(BigDecimal.ZERO) { a, b -> a + b } }
            .filterValues { it.signum() > 0 }
            .entries.sortedWith(compareByDescending<Map.Entry<MessageCategory, BigDecimal>> { it.value }.thenBy { it.key.ordinal })
        if (totals.isEmpty()) return emptyList()
        val shown = if (totals.size <= top + 1) totals else totals.take(top)
        val rest = totals.drop(shown.size).fold(BigDecimal.ZERO) { a, e -> a + e.value }
        val rows = shown.map { it.key as MessageCategory? to it.value } + if (rest.signum() > 0) listOf(null to rest) else emptyList()
        val total = rows.fold(BigDecimal.ZERO) { a, r -> a + r.second }
        val exact = rows.map { it.second.multiply(BigDecimal(100)).divide(total, 6, RoundingMode.HALF_UP) }
        val floors = exact.map { it.toInt() }.toMutableList()
        var left = 100 - floors.sum()
        exact.indices.sortedByDescending { exact[it].subtract(BigDecimal(floors[it])) }.forEach { i -> if (left > 0) { floors[i]++; left-- } }
        return rows.mapIndexed { i, r -> CategoryShare(r.first, r.second, floors[i]) }
    }

    /**
     * Widths for the segmented bar: each share's fraction, but never thinner than [minFraction] so a small category still shows,
     * scaled so the widths add up to 1. Empty in, empty out.
     */
    fun segments(shares: List<CategoryShare>, minFraction: Float = 0.05f): List<Float> {
        if (shares.isEmpty()) return emptyList()
        val total = shares.sumOf { it.percent }.coerceAtLeast(1)
        val raw = shares.map { (it.percent.toFloat() / total).coerceAtLeast(minFraction) }
        val sum = raw.sum()
        return raw.map { it / sum }
    }
}

/** The monthly spending limit a person sets for one card, and what it means for the cycle. */
object CardLimits {
    const val MIN = 1_000L
    const val MAX = 10_00_000L
    const val STEP = 1_000L
    /** Quick amounts in the limit sheet; the card's own credit limit is added when it is known. */
    val PRESETS = listOf(25_000L, 50_000L, 1_00_000L, 2_00_000L)
    /** From four fifths of the limit on, the card says it is close. */

    enum class Status { NONE, OK, NEAR, OVER }

    fun clamp(value: Long): Long = value.coerceIn(MIN, MAX)

    /** The nearest allowed amount: a multiple of [STEP] between [MIN] and [MAX] (a slider position lands here). */
    fun snap(value: Long): Long = clamp(((value + STEP / 2) / STEP) * STEP)

    /** The slider's 0..1 position of [value], and back. */
    fun fraction(value: Long): Float = ((clamp(value) - MIN).toFloat() / (MAX - MIN)).coerceIn(0f, 1f)
    fun fromFraction(fraction: Float): Long = snap(MIN + ((MAX - MIN) * fraction.coerceIn(0f, 1f)).toLong())

    /** The chips: the fixed amounts, plus the card's credit limit when it is known and in range, smallest first, no duplicates. */
    fun presets(creditLimit: Long?): List<Long> =
        (PRESETS + listOfNotNull(creditLimit?.takeIf { it in MIN..MAX })).distinct().sorted()

    /** How much of the limit is used, 0..1 (full bar when over); 0 without a limit. */
    fun progress(spent: BigDecimal, limit: Long?): Float {
        if (limit == null || limit <= 0L) return 0f
        return (spent.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** What is left of the limit; negative once over; null without a limit. */
    fun left(spent: BigDecimal, limit: Long?): BigDecimal? = limit?.takeIf { it > 0L }?.let { BigDecimal(it) - spent }

    fun status(spent: BigDecimal, limit: Long?): Status {
        if (limit == null || limit <= 0L) return Status.NONE
        val cap = BigDecimal(limit)
        return when {
            spent > cap -> Status.OVER
            // Exact arithmetic: 80% of the limit is "near", not a rounding away from it.
            spent.multiply(BigDecimal(5)) >= cap.multiply(BigDecimal(4)) -> Status.NEAR
            else -> Status.OK
        }
    }

    /** Whole days until the cycle starts again, counting today: on the last day of the cycle it is 1. Null when the cycle is not known. */
    fun resetsInDays(today: LocalDate, cycleEnd: LocalDate, known: Boolean): Int? {
        if (!known) return null
        val days = ChronoUnit.DAYS.between(today, cycleEnd) + 1
        return if (days >= 1) days.toInt() else null
    }
}

/** The stacked deck's shape: how many cards peek out behind the front one, and the page dots (a window when there are many cards). */
object DeckModel {
    const val MAX_DOTS = 7

    fun layersBehind(count: Int): Int = (count - 1).coerceIn(0, 2)

    fun clamp(page: Int, count: Int): Int = if (count <= 0) 0 else page.coerceIn(0, count - 1)

    /** A drag past this share of the deck's width sends the front card away. */
    const val COMMIT_FRACTION = 0.30f

    /** A flick faster than this (px per second) in the direction of the drag sends it away whatever the distance. */
    const val FLICK_PX_PER_S = 1_200f

    /**
     * Whether letting go of the front card at [dragPx] (negative = dragged left) with [velocityPxPerS] sends it away (to the next or
     * previous card) or lets it spring back. With fewer than two cards there is nowhere to go, so it always springs back.
     */
    fun commits(dragPx: Float, widthPx: Float, velocityPxPerS: Float, count: Int): Boolean {
        if (count < 2 || dragPx == 0f) return false
        val width = widthPx.coerceAtLeast(1f)
        val flicked = Math.abs(velocityPxPerS) > FLICK_PX_PER_S && (velocityPxPerS < 0f) == (dragPx < 0f)
        return Math.abs(dragPx) > width * COMMIT_FRACTION || flicked
    }

    /** One entry per dot shown: its card index and whether it is the current one. With many cards, a window that follows the page. */
    data class Dot(val index: Int, val on: Boolean)

    fun dots(count: Int, page: Int): List<Dot> {
        if (count <= 1) return emptyList()
        val current = clamp(page, count)
        val shown = minOf(count, MAX_DOTS)
        val first = (current - shown / 2).coerceIn(0, count - shown)
        return (first until first + shown).map { Dot(it, it == current) }
    }
}
