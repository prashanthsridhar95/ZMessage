package com.zyke.message.domain.investments

import java.math.BigDecimal
import java.time.LocalDate

/** What the person put money into. Provident fund from messages is not one of these: it is read automatically. */
enum class InvestmentKind(val label: String, val short: String, val isDeposit: Boolean = false) {
    FIXED_DEPOSIT("Fixed deposit", "FD", true),
    RECURRING_DEPOSIT("Recurring deposit", "RD", true),
    MUTUAL_FUND("Mutual fund", "MF"),
    STOCKS("Stocks", "Stocks"),
    GOLD("Gold", "Gold"),
    BONDS("Bonds", "Bonds"),
    PPF_NPS("PPF / NPS", "PPF"),
    OTHER("Other", "Other")
}

/** How interest is added to a deposit. [perYear] is 0 for the ones that are not compounded by the formula. */
enum class Compounding(val label: String, val perYear: Int) {
    MONTHLY("Monthly", 12),
    QUARTERLY("Quarterly", 4),
    HALF_YEARLY("Half-yearly", 2),
    YEARLY("Yearly", 1),
    SIMPLE("Simple interest", 0),

    /** The receipt states the amount at maturity; the interest is spread evenly between now and then. */
    AT_MATURITY_AS_GIVEN("As on receipt", 0)
}

enum class Payout(val label: String) { CUMULATIVE("Paid at maturity"), PERIODIC("Interest paid out regularly") }

enum class HoldingStatus(val label: String) { ACTIVE("Active"), MATURED("Matured"), CLOSED("Closed"), RENEWED("Renewed") }

/** The terms of a deposit. Either [tenureMonths] or [maturityDate] is entered; the other is derived. */
data class Terms(
    val ratePercent: BigDecimal = BigDecimal.ZERO,
    val compounding: Compounding = Compounding.QUARTERLY,
    val tenureMonths: Int? = null,
    val maturityDate: LocalDate? = null,
    /** What the receipt says will be paid at maturity. When present it replaces the calculation. */
    val maturityAmountGiven: BigDecimal? = null,
    val payout: Payout = Payout.CUMULATIVE,
    val monthlyInstallment: BigDecimal? = null,
    val installmentDay: Int? = null,
    /** The person's own count of installments paid, instead of counting from the start date. */
    val installmentsPaidOverride: Int? = null,
    /** Days before maturity to be reminded; 0 is the day itself. */
    val remindLeadDays: Set<Int> = DEFAULT_LEADS,
    val installmentReminder: Boolean = false
) {
    companion object {
        val DEFAULT_LEADS: Set<Int> = setOf(7, 0)
        val LEAD_OPTIONS: List<Int> = listOf(30, 15, 7, 3, 1, 0)
    }
}

/** The value on a day, for the history of a fund, share or gold holding. */
data class ValuePoint(val date: LocalDate, val value: BigDecimal, val invested: BigDecimal)

data class Holding(
    val id: String,
    val kind: InvestmentKind,
    val name: String,
    val institution: String = "",
    /** For a recurring deposit this is not used: what is deposited is worked out from the installments. */
    val invested: BigDecimal = BigDecimal.ZERO,
    val currentValue: BigDecimal? = null,
    val valueAsOf: LocalDate? = null,
    val startDate: LocalDate? = null,
    val notes: String = "",
    val receiptNo: String = "",
    val terms: Terms = Terms(),
    val status: HoldingStatus = HoldingStatus.ACTIVE,
    val closedOn: LocalDate? = null,
    /** What was received on closing. */
    val closedAmount: BigDecimal? = null,
    val history: List<ValuePoint> = emptyList(),
    /** The holding this one replaced when it was renewed. */
    val renewedFrom: String? = null
) {
    val isDeposit: Boolean get() = kind.isDeposit
    val isActiveish: Boolean get() = status == HoldingStatus.ACTIVE || status == HoldingStatus.MATURED

    companion object {
        const val MAX_HISTORY = 60
    }
}
