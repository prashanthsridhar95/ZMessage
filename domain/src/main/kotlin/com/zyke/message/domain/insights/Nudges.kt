package com.zyke.message.domain.insights

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.CategoryLimits
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.wishlist.WishItem
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class NudgeKind { PACE, OVER_LIMIT, LOW_BALANCE, OTHER }

/** One short, useful sentence for the person, optionally about a particular message. */
data class Nudge(val kind: NudgeKind, val text: String, val messageId: Long? = null)

/**
 * Says where spending is heading before a limit is hit, not after. It gives few nudges and each one carries a
 * number, because a pile of alerts gets ignored and one clear sentence gets read.
 */
object PaceNudges {
    private const val MAX_NUDGES = 3
    private const val NOTICE_FROM_USED = 0.4f
    private const val AHEAD_BY = 0.2f

    fun build(
        limits: List<CategoryLimits>,
        wishlist: List<WishItem>,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR
    ): List<Nudge> {
        val today = LocalDate.ofInstant(now, zone)
        val elapsed = cycle.dayNumber(today).toFloat() / cycle.length(today)
        val nudges = ArrayList<Nudge>()

        limits.forEach { entry ->
            val name = entry.category.displayString.lowercase()
            val daily = entry.daily
            val monthly = entry.monthly

            if (monthly != null && monthly.isOver) {
                var text = "You're ${money(monthly.deficit)} over your $name limit for the month."
                wishFor(entry.category, wishlist)?.let { text += " ${it.name} (${money(it.price)}) can wait until next month." }
                nudges += Nudge(NudgeKind.OVER_LIMIT, text)
            } else if (daily != null && daily.isOver) {
                nudges += Nudge(NudgeKind.OVER_LIMIT, "You're ${money(daily.deficit)} over today's $name limit.")
            } else if (monthly != null && monthly.used >= NOTICE_FROM_USED && monthly.used - elapsed >= AHEAD_BY) {
                val percent = (monthly.used * 100).toInt()
                var text = "It's day ${cycle.dayNumber(today)} of ${cycle.length(today)} and you've used $percent% of your $name limit."
                entry.perDayLeft?.let { text += " About ${money(it)} a day keeps you within it." }
                wishFor(entry.category, wishlist)?.let { text += " ${it.name} could wait until next month." }
                nudges += Nudge(NudgeKind.PACE, text)
            }
        }
        return nudges.sortedBy { it.kind != NudgeKind.OVER_LIMIT }.take(MAX_NUDGES)
    }

    private fun wishFor(category: MessageCategory, wishlist: List<WishItem>) = wishlist.firstOrNull { it.category == category }

    internal fun money(amount: java.math.BigDecimal): String {
        val whole = amount.stripTrailingZeros().scale() <= 0
        val format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = if (whole) 0 else 2
        }
        return "₹" + format.format(amount)
    }
}
