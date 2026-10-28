package com.zyke.message.domain.accounts

import java.time.LocalDate
import kotlin.math.abs

/** Works out the day of the month a card's statement is generated, from the dates its bill messages arrived. */
object StatementDays {
    private const val WINDOW = 2

    /**
     * The day most bills cluster around. Banks send the bill on the statement day or a day or two after, so days within
     * [WINDOW] of one another count as the same. Two or more bills must agree; a lone bill is taken as it is.
     */
    fun suggest(billDates: List<LocalDate>): Int? {
        val days = billDates.sortedDescending().map { it.dayOfMonth }
        if (days.isEmpty()) return null
        if (days.size == 1) return days.first()

        // The day with the most neighbours; the newest bill breaks a tie (days is newest first).
        val best = days.maxByOrNull { day -> days.count { near(it, day) } } ?: return null
        val cluster = days.filter { near(it, best) }
        if (cluster.size < 2) return null
        // The earliest day in the cluster is the statement itself; the others are the message coming later.
        return cluster.minOf { if (it > best + WINDOW) it - 31 else it }.let { if (it < 1) it + 31 else it }
    }

    private fun near(a: Int, b: Int): Boolean {
        val gap = abs(a - b)
        return minOf(gap, 31 - gap) <= WINDOW
    }
}
