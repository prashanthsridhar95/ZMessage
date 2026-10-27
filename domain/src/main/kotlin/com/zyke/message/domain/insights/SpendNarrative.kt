package com.zyke.message.domain.insights

import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.dashboard.FlowDirection
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A plain-English line about the month, written from the numbers so it can never disagree with them. */
object SpendNarrative {
    private const val SHARE_WORTH_MENTIONING = 0.25

    fun of(month: Dashboard): String? {
        if (month.isEmpty || month.direction != FlowDirection.SPENT) return null
        val parts = mutableListOf<String>()

        val top = month.categories.maxByOrNull { it.total }
        if (top != null && month.total.signum() > 0) {
            val share = top.total.toDouble() / month.total.toDouble()
            if (share >= SHARE_WORTH_MENTIONING) {
                parts += "${(share * 100).roundToInt()}% went on ${top.category.displayString.lowercase()}"
            }
        }
        val topVendor = month.vendors.maxByOrNull { it.total }
        if (topVendor != null && month.vendors.size > 1) {
            parts += "most at ${topVendor.merchant.displayName()} (${rupees(topVendor.total)})"
        }
        month.changePercent?.let {
            val percent = abs(it).roundToInt()
            if (percent >= 5) parts += "$percent% ${if (it >= 0) "up" else "down"} on the same days last month"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }
    }

    private fun com.zyke.message.domain.model.Merchant.displayName() = name

    private fun rupees(amount: BigDecimal): String =
        "₹" + NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply { maximumFractionDigits = 0 }.format(amount)
}
