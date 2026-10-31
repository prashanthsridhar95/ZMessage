package com.zyke.message.domain.loans

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.plan.PlanMatching
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class LoanKind(val label: String) {
    HOME("Home"),
    VEHICLE("Vehicle"),
    PERSONAL("Personal"),
    EDUCATION("Education"),
    OTHER("Other")
}

/** A loan the person entered. Nothing here is read from messages; the app only checks the messages for its EMIs. */
data class Loan(
    val id: String,
    val name: String,
    val kind: LoanKind,
    val principal: BigDecimal,
    /** Yearly interest, in percent (9 means 9%). */
    val annualRatePercent: BigDecimal,
    val tenureMonths: Int,
    /** The day the loan began; the first EMI falls on [emiDay] in the month after it. */
    val startDate: LocalDate,
    /** What the person says the EMI is, when it differs from the one worked out from the principal, rate and tenure. */
    val emiOverride: BigDecimal? = null,
    val emiDay: Int
) {
    /** The EMI in force: the person's own figure, else the standard formula. */
    val emi: BigDecimal get() = emiOverride ?: Loans.emi(principal, annualRatePercent, tenureMonths)
}

/** One instalment of the schedule. */
data class Instalment(
    val number: Int,
    val dueDate: LocalDate,
    val amount: BigDecimal,
    val interest: BigDecimal,
    val principal: BigDecimal,
    /** What is still owed once this instalment is paid. */
    val balanceAfter: BigDecimal
)

/** Where a loan stands today, worked out from its terms alone. */
data class LoanStatus(
    val loan: Loan,
    val emi: BigDecimal,
    val schedule: List<Instalment>,
    /** Instalments whose due date has come. */
    val dueCount: Int,
    /** What is left of the principal if every instalment due so far was paid on time: an estimate, not a bank figure. */
    val outstanding: BigDecimal,
    val principalRepaid: BigDecimal,
    /** 0 to 1: the share of the principal repaid by the schedule. */
    val fraction: Float,
    val nextDueDate: LocalDate?,
    val nextAmount: BigDecimal?,
    val isClosed: Boolean,
    /** Due instalments whose payment was found in the messages. */
    val seenInMessages: Int,
    val lastSeenOn: LocalDate?,
    /** The date this status is as of (the latest instalment due, else the start date). */
    val asOf: LocalDate
) {
    /** Instalments due that no message shows. */
    val notSeen: Int get() = (dueCount - seenInMessages).coerceAtLeast(0)
}

/** A payment out, as the matching sees it. */
data class LoanDebit(val date: LocalDate, val amount: BigDecimal, /** Payee and merchant names, lower case. */ val who: String)

object Loans {
    private val MC = MathContext(30)
    private val TWELVE_HUNDRED = BigDecimal(1200)

    /** The standard EMI: P r (1+r)^n / ((1+r)^n - 1), with r the monthly rate. Rounded to paise. */
    fun emi(principal: BigDecimal, annualRatePercent: BigDecimal, months: Int): BigDecimal {
        if (months <= 0 || principal.signum() <= 0) return BigDecimal.ZERO.setScale(2)
        if (annualRatePercent.signum() <= 0) return principal.divide(BigDecimal(months), 2, RoundingMode.HALF_UP)
        val r = annualRatePercent.divide(TWELVE_HUNDRED, MC)
        val growth = BigDecimal.ONE.add(r).pow(months, MC)
        return principal.multiply(r).multiply(growth).divide(growth.subtract(BigDecimal.ONE), MC).setScale(2, RoundingMode.HALF_UP)
    }

    fun dueDate(loan: Loan, number: Int): LocalDate {
        val month = YearMonth.from(loan.startDate).plusMonths(number.toLong())
        return month.atDay(loan.emiDay.coerceIn(1, month.lengthOfMonth()))
    }

    /**
     * Each month's split of the EMI into interest and principal. It stops once the balance is cleared; the last
     * instalment takes whatever is left, so rounding never leaves a few paise owing (and a too-small EMI leaves the
     * rest to the final instalment).
     */
    fun schedule(loan: Loan): List<Instalment> {
        if (loan.tenureMonths <= 0 || loan.principal.signum() <= 0) return emptyList()
        val monthly = loan.annualRatePercent.divide(TWELVE_HUNDRED, MC)
        val emi = loan.emi
        var balance = loan.principal
        val rows = ArrayList<Instalment>()
        for (number in 1..loan.tenureMonths) {
            val interest = balance.multiply(monthly).setScale(2, RoundingMode.HALF_UP)
            val owed = balance + interest
            val pay = if (number == loan.tenureMonths || owed <= emi) owed else emi
            val principalPart = pay - interest
            balance = (balance - principalPart).max(BigDecimal.ZERO)
            rows += Instalment(number, dueDate(loan, number), pay, interest, principalPart, balance)
            if (balance.signum() == 0) break
        }
        return rows
    }

    /** The principal still owed after [payments] instalments (0 gives the whole principal). */
    fun outstandingAfter(loan: Loan, payments: Int): BigDecimal = outstandingAfter(loan, schedule(loan), payments)

    fun outstandingAfter(loan: Loan, schedule: List<Instalment>, payments: Int): BigDecimal = when {
        payments <= 0 -> loan.principal
        payments >= schedule.size -> schedule.lastOrNull()?.balanceAfter ?: loan.principal
        else -> schedule[payments - 1].balanceAfter
    }

    fun status(loan: Loan, today: LocalDate, debits: List<LoanDebit> = emptyList()): LoanStatus {
        val schedule = schedule(loan)
        val dueCount = schedule.count { !it.dueDate.isAfter(today) }
        val outstanding = outstandingAfter(loan, schedule, dueCount)
        val repaid = (loan.principal - outstanding).max(BigDecimal.ZERO)
        val fraction = if (loan.principal.signum() > 0) (repaid.toFloat() / loan.principal.toFloat()).coerceIn(0f, 1f) else 0f
        val next = schedule.firstOrNull { it.dueDate.isAfter(today) }
        val matched = seen(loan, schedule.take(dueCount), debits)
        return LoanStatus(
            loan = loan,
            emi = loan.emi,
            schedule = schedule,
            dueCount = dueCount,
            outstanding = outstanding,
            principalRepaid = repaid,
            fraction = fraction,
            nextDueDate = next?.dueDate,
            nextAmount = next?.amount,
            isClosed = schedule.isNotEmpty() && next == null,
            seenInMessages = matched.size,
            lastSeenOn = matched.values.maxOrNull(),
            asOf = schedule.take(dueCount).lastOrNull()?.dueDate ?: loan.startDate
        )
    }

    /** Wider than a bank's usual clearing: an EMI is taken a few days either side of its day. */
    private const val DAYS_BEFORE = 3L
    private const val DAYS_AFTER = 7L

    /**
     * Which of the due instalments a payment out in [debits] accounts for (instalment number to the payment's date).
     * A payment counts only by name, amount and day, the same test a planned outgoing uses; one payment settles at
     * most one instalment. The split between interest and principal is never read from a payment.
     */
    fun seen(loan: Loan, due: List<Instalment>, debits: List<LoanDebit>): Map<Int, LocalDate> {
        if (due.isEmpty() || debits.isEmpty()) return emptyMap()
        val item = PlanItem(loan.id, PlanItemKind.OUTFLOW, loan.name, loan.emi, loan.emiDay)
        val used = HashSet<LoanDebit>()
        val found = LinkedHashMap<Int, LocalDate>()
        due.forEach { instalment ->
            val hit = debits.filter { it !in used }
                .filter { !it.date.isBefore(instalment.dueDate.minusDays(DAYS_BEFORE)) && !it.date.isAfter(instalment.dueDate.plusDays(DAYS_AFTER)) }
                .filter { PlanMatching.matches(item, it.who, it.amount) }
                .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, instalment.dueDate)) }
            if (hit != null) {
                used += hit
                found[instalment.number] = hit.date
            }
        }
        return found
    }

    /** The payments out in the messages (not credits, not card-bill payments), reduced to what the matching needs. */
    fun debitsFrom(messages: List<ClassifiedMessage>, zone: ZoneId): List<LoanDebit> = messages.mapNotNull { message ->
        val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
        if (tx.isReceived || tx.isCardPayment) return@mapNotNull null
        val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
        LoanDebit(java.time.Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), amount, "${tx.payee} ${message.merchant.name}".lowercase())
    }

    fun statuses(loans: List<Loan>, today: LocalDate, debits: List<LoanDebit> = emptyList()): List<LoanStatus> =
        loans.map { status(it, today, debits) }.sortedWith(compareBy<LoanStatus> { it.isClosed }.thenBy { it.nextDueDate ?: LocalDate.MAX })
}
