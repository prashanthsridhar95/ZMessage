package com.zyke.message.domain.networth

import java.math.BigDecimal

/** One line of the short net worth summary: a group of the report's lines added up. */
data class BreakdownRow(val label: String, val amount: BigDecimal, val isLiability: Boolean)

/**
 * The few rows the Overview's net worth panel shows, worked out from the same [NetWorthReport] as the full page, so the two
 * always agree: the assets rows add up to [NetWorthReport.totalAssets] and the liabilities rows to [NetWorthReport.totalLiabilities].
 */
object NetWorthBreakdown {
    fun of(report: NetWorthReport): List<BreakdownRow> {
        fun sum(lines: List<NetWorthLine>, prefix: String) =
            lines.filter { it.key.startsWith(prefix) }.fold(BigDecimal.ZERO) { s, l -> s + (l.amount ?: BigDecimal.ZERO) }
        val rows = listOf(
            BreakdownRow("Bank balances", sum(report.assets, "acct-"), false),
            BreakdownRow("Investments", sum(report.assets, "holding-"), false),
            BreakdownRow("Cash and other assets", sum(report.assets, "item-"), false),
            BreakdownRow("Card dues", sum(report.liabilities, "card-"), true),
            BreakdownRow("Loans", sum(report.liabilities, "loan-"), true),
            BreakdownRow("Other debts", sum(report.liabilities, "item-"), true)
        )
        return rows.filter { it.amount.signum() != 0 }
    }
}
