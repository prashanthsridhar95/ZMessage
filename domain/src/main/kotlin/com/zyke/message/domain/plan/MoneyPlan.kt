package com.zyke.message.domain.plan

import com.zyke.message.domain.cycle.CycleMode
import java.math.BigDecimal

enum class PlanItemKind(val label: String) {
    /** Regular money going out: parents, charity, rent, a loan. */
    OUTFLOW("Regular outflow"),

    /** Money put away: a SIP, a recurring deposit, a policy. */
    INVESTMENT("Investment")
}

/** One regular payment the person expects every month. */
data class PlanItem(
    val id: String,
    val kind: PlanItemKind,
    /** What the person calls it, e.g. "Parents" or "Index fund SIP"; also used to spot the payment in the messages. */
    val name: String,
    val amount: BigDecimal,
    /** Day of the month it usually happens, when it has one. */
    val dayOfMonth: Int? = null
)

/**
 * What the person tells the app about their month, so the dashboard can show what is theirs to spend. All of it is
 * approximate and optional, and it never leaves the phone.
 */
data class MoneyPlan(
    /** Usual monthly salary credited, and the day it normally arrives. */
    val salary: BigDecimal? = null,
    val salaryDay: Int? = null,
    /** Paid on the last working day of the month (Monday to Friday), whatever date that is; [salaryDay] is then not used. */
    val salaryOnLastWorkingDay: Boolean = false,
    val items: List<PlanItem> = emptyList(),
    /** How much to put aside each month. */
    val savingsGoal: BigDecimal? = null,
    /** What the person wants to spend on credit cards in a month, all cards together. */
    val cardSpendLimit: BigDecimal? = null,
    /** Where the month starts for graphs, limits and the report card, and the day when it is the person's own. */
    val cycleMode: CycleMode = CycleMode.SALARY,
    val cycleDay: Int? = null
) {
    val outflows: List<PlanItem> get() = items.filter { it.kind == PlanItemKind.OUTFLOW }
    val investments: List<PlanItem> get() = items.filter { it.kind == PlanItemKind.INVESTMENT }

    /** The day of the month the salary arrives in [month], when the person has said. */
    fun salaryDayIn(month: java.time.YearMonth): Int? = if (salaryOnLastWorkingDay) SalaryDays.lastWorkingDay(month).dayOfMonth else salaryDay

    /** Whether the person has said when the salary arrives, in either way. */
    val hasSalaryDay: Boolean get() = salaryOnLastWorkingDay || salaryDay != null

    /** Nothing has been filled in. */
    val isEmpty: Boolean get() = salary == null && items.isEmpty() && savingsGoal == null
}
