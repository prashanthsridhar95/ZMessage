package com.zyke.message.domain.feed

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When a dismissed bill comes back: three days before it is due, then on the due date, then never. */
object Dismissals {
    private const val REMIND_DAYS_BEFORE = 3L

    /** Keys of bills are timed; this is how the repository and the builder agree on which they are. */
    fun isTimed(key: String) = key.startsWith("bill-")

    fun isHidden(
        key: String,
        dueDate: LocalDate?,
        today: LocalDate,
        zone: ZoneId,
        dismissed: Set<String>,
        dismissedAt: Map<String, Long>
    ): Boolean {
        // Dismissed for good, or by an older version that kept no time.
        if (key in dismissed) return true
        val at = dismissedAt[key] ?: return false
        // A bill with no due date has nothing to count back from.
        if (dueDate == null) return true
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val early = dueDate.minusDays(REMIND_DAYS_BEFORE)
        return when {
            // Dismissed long before: back three days before it is due.
            day.isBefore(early) -> today.isBefore(early)
            // Dismissed in those last days: back on the due date.
            day.isBefore(dueDate) -> today.isBefore(dueDate)
            // Dismissed on or after the due date: that is the last time.
            else -> true
        }
    }
}
