package com.zyke.message.domain.calendar

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which way the calendar moved: forward in time, back, or not at all. */
enum class PageDirection { NEXT, PREVIOUS, NONE }

/** The dots under a day: up to [shown] colours, and a "+" when there were more. */
data class DotPlan(val shown: Int, val more: Boolean)

/**
 * The arithmetic of the calendar's paging and day cells, pure so it is tested without a screen: which way a month change
 * went (the slide follows it), how far a swipe must travel to turn the page, how far the page slides, how many dots a day
 * shows and the sentence a screen reader hears for a day.
 */
object CalendarPaging {
    /** The month grid slides at most this many dp ... */
    const val SLIDE_MAX_DP = 24f

    /** ... and at most this share of its width, whichever is smaller. */
    const val SLIDE_MAX_SHARE = 0.3f

    /** A swipe turns the page once it has travelled this share of the grid's width. */
    const val SWIPE_SHARE = 0.18f

    /** ... but never less than this many dp, so a small drift while scrolling is not a page turn. */
    const val SWIPE_MIN_DP = 48f

    const val MAX_DOTS = 3

    fun direction(from: YearMonth, to: YearMonth): PageDirection = when {
        to.isAfter(from) -> PageDirection.NEXT
        to.isBefore(from) -> PageDirection.PREVIOUS
        else -> PageDirection.NONE
    }

    /** Sideways travel in dp for a grid [widthDp] wide. */
    fun slideDp(widthDp: Float): Float = minOf(SLIDE_MAX_DP, widthDp * SLIDE_MAX_SHARE).coerceAtLeast(0f)

    /** The side the new page comes from: +1 from the right (next month), -1 from the left (previous), 0 when it did not change. */
    fun enterSign(direction: PageDirection): Int = when (direction) {
        PageDirection.NEXT -> 1
        PageDirection.PREVIOUS -> -1
        PageDirection.NONE -> 0
    }

    /** How far, in the same unit as the drag, a swipe has to go before it turns the page. */
    fun swipeThreshold(widthDp: Float): Float = maxOf(SWIPE_MIN_DP, widthDp * SWIPE_SHARE)

    /** A drag of [travelled] to the right is positive. Left past the threshold is the next month; right past it the previous. */
    fun swipe(travelled: Float, threshold: Float): PageDirection = when {
        travelled <= -threshold -> PageDirection.NEXT
        travelled >= threshold -> PageDirection.PREVIOUS
        else -> PageDirection.NONE
    }

    fun shifted(month: YearMonth, direction: PageDirection): YearMonth = when (direction) {
        PageDirection.NEXT -> month.plusMonths(1)
        PageDirection.PREVIOUS -> month.minusMonths(1)
        PageDirection.NONE -> month
    }

    /** Three dots at most; with more kinds than that the third is followed by a "+". */
    fun dots(kinds: Int): DotPlan = DotPlan(kinds.coerceIn(0, MAX_DOTS), kinds > MAX_DOTS)

    /** Bills and auto-debits still to pay on a day. */
    fun billsDue(entries: List<CalendarEntry>): Int =
        entries.count { (it.kind == CalendarKind.BILL || it.kind == CalendarKind.DEBIT) && !it.done }

    /**
     * What a screen reader hears for a day: "Tuesday 12 November, today, 2 bills due, 1 other item". A day with nothing on it
     * says "nothing planned".
     */
    fun spoken(date: LocalDate, today: LocalDate, entries: List<CalendarEntry>, locale: Locale = Locale.getDefault()): String {
        val bills = billsDue(entries)
        val other = entries.size - bills
        val parts = ArrayList<String>()
        parts += DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(date)
        if (date == today) parts += "today"
        if (bills > 0) parts += if (bills == 1) "1 bill due" else "$bills bills due"
        if (other > 0) parts += if (other == 1) "1 other item" else "$other other items"
        if (entries.isEmpty()) parts += "nothing planned"
        return parts.joinToString(", ")
    }
}
