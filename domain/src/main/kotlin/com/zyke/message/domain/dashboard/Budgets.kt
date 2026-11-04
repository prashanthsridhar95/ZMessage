package com.zyke.message.domain.dashboard

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class CarryOver(val label: String) {
    RESET("Reset each cycle"),
    CARRY("Carry over leftovers")
}

/** Money moved from one budget to another within one cycle. */
data class BudgetMove(val from: MessageCategory, val to: MessageCategory, val amount: BigDecimal)

/**
 * What sits on top of the standing monthly budgets: each budget's carry-over choice, and the money moved between
 * budgets, keyed by the first day of the cycle it was moved in.
 */
data class BudgetAdjustments(
    val carry: Map<MessageCategory, CarryOver> = emptyMap(),
    /** The first cycle a budget has carried leftovers from, so turning carry-over on does not dig up the past. */
    val carryFrom: Map<MessageCategory, LocalDate> = emptyMap(),
    val moves: Map<LocalDate, List<BudgetMove>> = emptyMap()
)

/** One monthly budget in one cycle. */
data class BudgetLine(
    val category: MessageCategory,
    val assigned: BigDecimal,
    /** Net of money moved in and out this cycle. */
    val moved: BigDecimal,
    val carriedIn: BigDecimal,
    val spent: BigDecimal,
    val carry: CarryOver
) {
    val available: BigDecimal get() = assigned + moved + carriedIn

    /** Negative once overspent. */
    val left: BigDecimal get() = available - spent
    val isOver: Boolean get() = left.signum() < 0
    val used: Float get() = if (available.signum() > 0) spent.toFloat() / available.toFloat() else if (spent.signum() > 0) 1f else 0f
}

data class BudgetStatus(
    val cycle: DateRange,
    val lines: List<BudgetLine>,
    val assignedTotal: BigDecimal,
    /** Spendable money not yet put in a budget; null when there is no plan to take it from. Negative if over-assigned. */
    val unassigned: BigDecimal?
)

/** Carry-over, moving money between budgets, and what is not yet assigned, on top of the monthly limits. */
object Budgets {
    private const val MAX_CHAIN = 24

    fun status(
        monthly: Map<MessageCategory, BigDecimal>,
        adjustments: BudgetAdjustments,
        messages: List<ClassifiedMessage>,
        cycleRule: CycleRule,
        today: LocalDate,
        zone: ZoneId,
        spendable: BigDecimal?
    ): BudgetStatus {
        val range = cycleRule.containing(today)
        val active = monthly.filterValues { it.signum() > 0 }
        val lines = active.keys.map { line(active, adjustments, messages, cycleRule, range, it, zone, 0) }
            .sortedByDescending { it.assigned }
        val total = active.values.fold(BigDecimal.ZERO, BigDecimal::add)
        return BudgetStatus(range, lines, total, spendable?.subtract(total))
    }

    /** One category's position this cycle, or null if it has no monthly budget. */
    fun lineFor(
        monthly: Map<MessageCategory, BigDecimal>,
        adjustments: BudgetAdjustments,
        messages: List<ClassifiedMessage>,
        cycleRule: CycleRule,
        today: LocalDate,
        zone: ZoneId,
        category: MessageCategory
    ): BudgetLine? {
        if ((monthly[category] ?: BigDecimal.ZERO).signum() <= 0) return null
        val active = monthly.filterValues { it.signum() > 0 }
        return line(active, adjustments, messages, cycleRule, cycleRule.containing(today), category, zone, 0)
    }

    private fun line(
        monthly: Map<MessageCategory, BigDecimal>,
        adjustments: BudgetAdjustments,
        messages: List<ClassifiedMessage>,
        rule: CycleRule,
        range: DateRange,
        category: MessageCategory,
        zone: ZoneId,
        depth: Int
    ): BudgetLine {
        val carry = adjustments.carry[category] ?: CarryOver.RESET
        val moved = adjustments.moves[range.start].orEmpty().fold(BigDecimal.ZERO) { sum, m ->
            when (category) {
                m.to -> sum + m.amount
                m.from -> sum - m.amount
                else -> sum
            }
        }
        var carriedIn = BigDecimal.ZERO
        val from = adjustments.carryFrom[category]
        if (carry == CarryOver.CARRY && from != null && depth < MAX_CHAIN) {
            val prev = rule.containing(range.start.minusDays(1))
            if (prev.start >= from) carriedIn = line(monthly, adjustments, messages, rule, prev, category, zone, depth + 1).left.max(BigDecimal.ZERO)
        }
        return BudgetLine(category, monthly[category] ?: BigDecimal.ZERO, moved, carriedIn, spentIn(messages, range, category, zone), carry)
    }

    /** What went out in [category] in [range]; payments the person set aside (left out, or transfers) do not count. */
    fun spentIn(messages: List<ClassifiedMessage>, range: DateRange, category: MessageCategory, zone: ZoneId): BigDecimal =
        messages.fold(BigDecimal.ZERO) { sum, message ->
            if (message.category != category) return@fold sum
            val amount = Counting.signedSpend(message) ?: return@fold sum
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(range.start) || date.isAfter(range.endInclusive)) sum else sum + amount
        }

    /** Choosing carry-over starts it in the cycle that begins on [cycleStart]; choosing reset forgets where it began. */
    fun setCarry(adjustments: BudgetAdjustments, category: MessageCategory, carry: CarryOver, cycleStart: LocalDate): BudgetAdjustments {
        val wasCarrying = adjustments.carry[category] == CarryOver.CARRY && adjustments.carryFrom.containsKey(category)
        val carryFrom = when {
            carry == CarryOver.RESET -> adjustments.carryFrom - category
            wasCarrying -> adjustments.carryFrom
            else -> adjustments.carryFrom + (category to cycleStart)
        }
        return adjustments.copy(carry = adjustments.carry + (category to carry), carryFrom = carryFrom)
    }

    /** Records a move. Returns [adjustments] unchanged if it is not valid: same budget, no amount, or more than the source has left. */
    fun move(adjustments: BudgetAdjustments, status: BudgetStatus, from: MessageCategory, to: MessageCategory, amount: BigDecimal): BudgetAdjustments {
        val source = status.lines.firstOrNull { it.category == from } ?: return adjustments
        if (from == to || amount.signum() <= 0 || status.lines.none { it.category == to } || amount > source.left) return adjustments
        val start = status.cycle.start
        return adjustments.copy(moves = adjustments.moves + (start to (adjustments.moves[start].orEmpty() + BudgetMove(from, to, amount))))
    }

    /** Takes back the last move of the cycle (for Undo). */
    fun undoMove(adjustments: BudgetAdjustments, cycleStart: LocalDate): BudgetAdjustments {
        val moves = adjustments.moves[cycleStart] ?: return adjustments
        return adjustments.copy(moves = adjustments.moves + (cycleStart to moves.dropLast(1)))
    }
}

/** Brings the old per-cycle envelope plans into the budgets. Pure, so it can be tested and run again safely. */
object BudgetMigration {
    /** The result of folding old envelopes into the current budgets. */
    data class Result(val monthly: Map<MessageCategory, BigDecimal>, val adjustments: BudgetAdjustments)

    private const val MAX_GAP_DAYS = 32L

    /**
     * [assignedByCycle] is each old cycle's envelope amounts. The most recent cycle that has any becomes the monthly
     * budgets, but never over a budget the person already has. Carry-over choices and moves are copied where the budgets
     * have none yet. A carrying envelope keeps carrying from the start of its unbroken run of cycles.
     */
    fun fromEnvelopes(
        monthly: Map<MessageCategory, BigDecimal>,
        adjustments: BudgetAdjustments,
        assignedByCycle: Map<LocalDate, Map<MessageCategory, BigDecimal>>,
        legacy: BudgetAdjustments
    ): Result {
        val withAmounts = assignedByCycle.filterValues { it.isNotEmpty() }
        val latestStart = withAmounts.keys.maxOrNull()
        val latest = latestStart?.let { withAmounts.getValue(it) }.orEmpty()
        val merged = monthly + latest.filter { (category, amount) -> amount.signum() > 0 && (monthly[category]?.signum() ?: 0) <= 0 }
        val carryFrom = HashMap<MessageCategory, LocalDate>()
        legacy.carry.forEach { (category, mode) ->
            if (mode != CarryOver.CARRY) return@forEach
            val starts = withAmounts.filterValues { it.containsKey(category) }.keys.sortedDescending()
            if (starts.isEmpty()) return@forEach
            var from = starts.first()
            for (start in starts.drop(1)) {
                if (java.time.temporal.ChronoUnit.DAYS.between(start, from) > MAX_GAP_DAYS) break
                from = start
            }
            carryFrom[category] = from
        }
        val result = BudgetAdjustments(
            carry = legacy.carry + adjustments.carry,
            carryFrom = carryFrom + adjustments.carryFrom,
            moves = legacy.moves + adjustments.moves
        )
        return Result(merged, result)
    }
}
