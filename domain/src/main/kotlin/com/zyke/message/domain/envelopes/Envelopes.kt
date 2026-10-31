package com.zyke.message.domain.envelopes

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CarryOver(val label: String) {
    RESET("Reset each cycle"),
    CARRY("Carry over leftovers")
}

/** Money moved from one envelope to another within one cycle. */
data class EnvelopeMove(val from: MessageCategory, val to: MessageCategory, val amount: BigDecimal)

/** What the person assigned at the start of one cycle, and the moves they made since. */
data class CyclePlan(
    val assigned: Map<MessageCategory, BigDecimal> = emptyMap(),
    val moves: List<EnvelopeMove> = emptyList()
)

/** Every cycle's plan, keyed by the cycle's first day, and each envelope's carry-over choice. */
data class EnvelopeBook(
    val cycles: Map<LocalDate, CyclePlan> = emptyMap(),
    val carry: Map<MessageCategory, CarryOver> = emptyMap()
)

interface EnvelopeRepository {
    val book: StateFlow<EnvelopeBook>
    fun save(book: EnvelopeBook)
}

object NoEnvelopes : EnvelopeRepository {
    override val book: StateFlow<EnvelopeBook> = MutableStateFlow(EnvelopeBook())
    override fun save(book: EnvelopeBook) = Unit
}

data class EnvelopeLine(
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

data class EnvelopeStatus(
    val cycle: DateRange,
    val lines: List<EnvelopeLine>,
    val assignedTotal: BigDecimal,
    /** Spendable money not yet put in an envelope; null when there is no plan to take it from. Negative if over-assigned. */
    val unassigned: BigDecimal?
)

object Envelopes {
    private const val MAX_CHAIN = 24

    fun status(
        book: EnvelopeBook,
        messages: List<ClassifiedMessage>,
        cycleRule: CycleRule,
        today: LocalDate,
        zone: ZoneId,
        spendable: BigDecimal?
    ): EnvelopeStatus {
        val range = cycleRule.containing(today)
        val plan = book.cycles[range.start] ?: CyclePlan()
        val lines = plan.assigned.keys.map { line(book, messages, cycleRule, range, it, zone, 0) }
            .sortedByDescending { it.assigned }
        val total = plan.assigned.values.fold(BigDecimal.ZERO, BigDecimal::add)
        return EnvelopeStatus(range, lines, total, spendable?.subtract(total))
    }

    private fun line(
        book: EnvelopeBook,
        messages: List<ClassifiedMessage>,
        rule: CycleRule,
        range: DateRange,
        category: MessageCategory,
        zone: ZoneId,
        depth: Int
    ): EnvelopeLine {
        val plan = book.cycles[range.start] ?: CyclePlan()
        val carry = book.carry[category] ?: CarryOver.RESET
        val moved = plan.moves.fold(BigDecimal.ZERO) { sum, m ->
            when (category) {
                m.to -> sum + m.amount
                m.from -> sum - m.amount
                else -> sum
            }
        }
        var carriedIn = BigDecimal.ZERO
        if (carry == CarryOver.CARRY && depth < MAX_CHAIN) {
            val prev = rule.containing(range.start.minusDays(1))
            if (book.cycles[prev.start]?.assigned?.containsKey(category) == true) {
                carriedIn = line(book, messages, rule, prev, category, zone, depth + 1).left.max(BigDecimal.ZERO)
            }
        }
        return EnvelopeLine(category, plan.assigned[category] ?: BigDecimal.ZERO, moved, carriedIn, spentIn(messages, range, category, zone), carry)
    }

    /** What went out in [category] in [range]; payments the person set aside (left out, or transfers) do not count. */
    fun spentIn(messages: List<ClassifiedMessage>, range: DateRange, category: MessageCategory, zone: ZoneId): BigDecimal =
        messages.fold(BigDecimal.ZERO) { sum, message ->
            val tx = message.content as? MessageContent.Transaction ?: return@fold sum
            if (tx.isReceived || message.setAside != null || message.category != category) return@fold sum
            val amount = tx.amount.toBigDecimalOrNull() ?: return@fold sum
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(range.start) || date.isAfter(range.endInclusive)) sum else sum + amount
        }

    fun assign(book: EnvelopeBook, cycleStart: LocalDate, category: MessageCategory, amount: BigDecimal): EnvelopeBook {
        val plan = book.cycles[cycleStart] ?: CyclePlan()
        val assigned = if (amount.signum() > 0) plan.assigned + (category to amount) else plan.assigned - category
        return book.copy(cycles = book.cycles + (cycleStart to plan.copy(assigned = assigned)))
    }

    /** Starts [cycleStart] with the amounts of [previousStart], if it has none yet. */
    fun startFrom(book: EnvelopeBook, cycleStart: LocalDate, previousStart: LocalDate): EnvelopeBook {
        val previous = book.cycles[previousStart] ?: return book
        if (book.cycles[cycleStart]?.assigned?.isNotEmpty() == true) return book
        return book.copy(cycles = book.cycles + (cycleStart to CyclePlan(previous.assigned)))
    }

    fun setCarry(book: EnvelopeBook, category: MessageCategory, carry: CarryOver): EnvelopeBook =
        book.copy(carry = book.carry + (category to carry))

    /** Records a move. Returns the book unchanged if it is not valid: same envelope, no amount, or more than the source has left. */
    fun move(book: EnvelopeBook, status: EnvelopeStatus, from: MessageCategory, to: MessageCategory, amount: BigDecimal): EnvelopeBook {
        val source = status.lines.firstOrNull { it.category == from } ?: return book
        if (from == to || amount.signum() <= 0 || status.lines.none { it.category == to } || amount > source.left) return book
        val plan = book.cycles[status.cycle.start] ?: return book
        return book.copy(cycles = book.cycles + (status.cycle.start to plan.copy(moves = plan.moves + EnvelopeMove(from, to, amount))))
    }

    /** Takes back the last move of the cycle (for Undo). */
    fun undoMove(book: EnvelopeBook, cycleStart: LocalDate): EnvelopeBook {
        val plan = book.cycles[cycleStart] ?: return book
        return book.copy(cycles = book.cycles + (cycleStart to plan.copy(moves = plan.moves.dropLast(1))))
    }
}
