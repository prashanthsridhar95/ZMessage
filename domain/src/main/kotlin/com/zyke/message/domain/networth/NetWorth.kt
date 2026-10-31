package com.zyke.message.domain.networth

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.loans.LoanStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What a manual entry is. Liabilities are the kinds with [isLiability]. */
enum class NetWorthKind(val label: String, val isLiability: Boolean = false) {
    CASH("Cash"),
    FIXED_DEPOSIT("Fixed deposit"),
    MUTUAL_FUNDS("Mutual funds"),
    STOCKS("Stocks"),
    RETIREMENT("EPF / PPF / NPS"),
    GOLD("Gold"),
    PROPERTY("Property"),
    OTHER_ASSET("Other asset"),
    OTHER_DEBT("Other debt", isLiability = true)
}

/** One asset or debt the person entered, with the day the amount was true. */
data class NetWorthItem(
    val id: String,
    val name: String,
    val kind: NetWorthKind,
    val amount: BigDecimal,
    val asOf: LocalDate,
    val note: String = ""
)

enum class LineSource {
    /** Read from the person's messages. */
    MESSAGES,

    /** Typed in by the person. */
    ENTERED,

    /** Worked out from a loan's terms and its schedule. */
    LOANS
}

/** One row of the net-worth page. Every row says where its number came from and when it was last true. */
data class NetWorthLine(
    val key: String,
    val name: String,
    val kindLabel: String,
    /** Null when the number is not known; such a row is listed but counts for nothing. */
    val amount: BigDecimal?,
    val source: LineSource,
    val asOf: LocalDate?,
    /** The value is older than [NetWorth.STALE_DAYS] days. */
    val stale: Boolean,
    val note: String = "",
    /** The manual entry to edit, when it is one. */
    val itemId: String? = null
)

data class NetWorthReport(
    val assets: List<NetWorthLine>,
    val liabilities: List<NetWorthLine>,
    val totalAssets: BigDecimal,
    val totalLiabilities: BigDecimal,
    /** Assets minus liabilities, of the values that are known. */
    val netWorth: BigDecimal,
    val staleCount: Int,
    /** Rows with no known amount. */
    val unknownCount: Int
)

object NetWorth {
    const val STALE_DAYS = 60L

    fun isStale(asOf: LocalDate?, today: LocalDate): Boolean = asOf != null && ChronoUnit.DAYS.between(asOf, today) > STALE_DAYS

    /** What a credit card owes: the unpaid part of its latest bill plus what was spent since it. Null when no message says. */
    fun cardOwed(account: AccountSummary): BigDecimal? {
        val bill = account.dueAmount
        val unbilled = account.unbilled
        if (bill == null && unbilled == null) return null
        val billOwing = when {
            bill == null || account.billPaid -> BigDecimal.ZERO
            else -> (bill - (account.paidSinceBill ?: BigDecimal.ZERO)).max(BigDecimal.ZERO)
        }
        return billOwing + (unbilled ?: BigDecimal.ZERO)
    }

    fun build(
        items: List<NetWorthItem>,
        accounts: List<AccountSummary>,
        loans: List<LoanStatus>,
        today: LocalDate
    ): NetWorthReport {
        val assets = ArrayList<NetWorthLine>()
        val liabilities = ArrayList<NetWorthLine>()

        accounts.filter { it.kind == AccountKind.BANK_ACCOUNT }.forEach { account ->
            assets += NetWorthLine(
                key = "acct-${account.key}", name = account.title,
                kindLabel = if (account.isProvidentFund) "Provident fund" else "Bank balance",
                amount = account.balance, source = LineSource.MESSAGES, asOf = account.balanceAsOf,
                stale = isStale(account.balanceAsOf, today),
                note = if (account.balance == null) "No balance in your messages" else ""
            )
        }
        accounts.filter { it.kind == AccountKind.CREDIT_CARD }.forEach { card ->
            val owed = cardOwed(card)
            val asOf = card.billedOn ?: card.statementOn
            liabilities += NetWorthLine(
                key = "card-${card.key}", name = card.title, kindLabel = "Credit card",
                amount = owed, source = LineSource.MESSAGES, asOf = asOf, stale = isStale(asOf, today),
                note = if (owed == null) "No bill or spend seen in your messages" else ""
            )
        }
        loans.filter { !it.isClosed }.forEach { status ->
            liabilities += NetWorthLine(
                key = "loan-${status.loan.id}", name = status.loan.name, kindLabel = "${status.loan.kind.label} loan",
                amount = status.outstanding, source = LineSource.LOANS, asOf = status.asOf, stale = false,
                note = "Estimated from the schedule, as if every EMI was paid on time"
            )
        }
        items.forEach { item ->
            val line = NetWorthLine(
                key = "item-${item.id}", name = item.name, kindLabel = item.kind.label, amount = item.amount,
                source = LineSource.ENTERED, asOf = item.asOf, stale = isStale(item.asOf, today), note = item.note, itemId = item.id
            )
            if (item.kind.isLiability) liabilities += line else assets += line
        }

        val sorted = { lines: List<NetWorthLine> -> lines.sortedWith(compareByDescending<NetWorthLine> { it.amount != null }.thenByDescending { it.amount }) }
        val a = sorted(assets)
        val l = sorted(liabilities)
        val totalAssets = a.fold(BigDecimal.ZERO) { sum, line -> sum + (line.amount ?: BigDecimal.ZERO) }
        val totalLiabilities = l.fold(BigDecimal.ZERO) { sum, line -> sum + (line.amount ?: BigDecimal.ZERO) }
        val all = a + l
        return NetWorthReport(
            assets = a, liabilities = l, totalAssets = totalAssets, totalLiabilities = totalLiabilities,
            netWorth = totalAssets - totalLiabilities,
            staleCount = all.count { it.stale }, unknownCount = all.count { it.amount == null }
        )
    }
}
