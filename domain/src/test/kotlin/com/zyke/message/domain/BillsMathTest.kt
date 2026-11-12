package com.zyke.message.domain

import com.zyke.message.domain.bills.BillsMath
import com.zyke.message.domain.bills.DueTone
import com.zyke.message.domain.calendar.CalendarEntry
import com.zyke.message.domain.calendar.CalendarKind
import com.zyke.message.domain.calendar.CalendarPaging
import com.zyke.message.domain.calendar.PageDirection
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillsMathTest {
    private val today = LocalDate.of(2026, 11, 12)
    private fun bd(v: Int) = BigDecimal(v)

    @Test
    fun dueCaptionsAndTonesFollowTheDistance() {
        assertEquals("Overdue by 2 days", BillsMath.dueCaption(today.minusDays(2), today, Locale.ENGLISH))
        assertEquals("Overdue by 1 day", BillsMath.dueCaption(today.minusDays(1), today, Locale.ENGLISH))
        assertEquals("Due today", BillsMath.dueCaption(today, today, Locale.ENGLISH))
        assertEquals("Due tomorrow", BillsMath.dueCaption(today.plusDays(1), today, Locale.ENGLISH))
        assertEquals("Due in 3 days", BillsMath.dueCaption(today.plusDays(3), today, Locale.ENGLISH))
        assertEquals("Due in 7 days", BillsMath.dueCaption(today.plusDays(7), today, Locale.ENGLISH))
        assertEquals("Due 25 Nov", BillsMath.dueCaption(LocalDate.of(2026, 11, 25), today, Locale.ENGLISH))
        assertEquals(DueTone.OVERDUE, BillsMath.tone(today.minusDays(1), today))
        assertEquals(DueTone.TODAY, BillsMath.tone(today, today))
        assertEquals(DueTone.SOON, BillsMath.tone(today.plusDays(3), today))
        assertEquals(DueTone.LATER, BillsMath.tone(today.plusDays(4), today))
    }

    @Test
    fun partPaymentFraction() {
        assertEquals(0f, BillsMath.partFraction(bd(1000), null), 0f)
        assertEquals(0f, BillsMath.partFraction(bd(1000), BigDecimal.ZERO), 0f)
        assertEquals(0.25f, BillsMath.partFraction(bd(1000), bd(250)), 0.0001f)
        assertEquals(1f, BillsMath.partFraction(bd(1000), bd(1500)), 0f)
        assertEquals(0f, BillsMath.partFraction(BigDecimal.ZERO, bd(10)), 0f)
        assertEquals(bd(750), BillsMath.left(bd(1000), bd(250)))
        assertEquals(BigDecimal.ZERO, BillsMath.left(bd(1000), bd(1500)))
        assertEquals(bd(1000), BillsMath.left(bd(1000), null))
    }

    @Test
    fun thisMonthScope() {
        assertTrue(BillsMath.countsForThisMonth(today.minusMonths(1), today))
        assertTrue(BillsMath.countsForThisMonth(LocalDate.of(2026, 11, 30), today))
        assertFalse(BillsMath.countsForThisMonth(LocalDate.of(2026, 12, 1), today))
        assertTrue(BillsMath.paidThisMonth(LocalDate.of(2026, 11, 1), today))
        assertFalse(BillsMath.paidThisMonth(LocalDate.of(2026, 10, 31), today))
    }

    @Test
    fun summaryProgress() {
        val s = BillsMath.summary(listOf(bd(300), bd(100)), listOf(bd(600)))
        assertEquals(bd(400), s.dueTotal)
        assertEquals(bd(600), s.paidTotal)
        assertEquals(3, s.count)
        assertEquals(0.6f, s.fraction, 0.0001f)
        assertEquals("1 of 3 paid", BillsMath.progressCaption(s))
        assertFalse(s.allPaid)

        val done = BillsMath.summary(emptyList(), listOf(bd(5), bd(5)))
        assertTrue(done.allPaid)
        assertEquals(1f, done.fraction, 0f)
        assertEquals("All 2 paid", BillsMath.progressCaption(done))

        val none = BillsMath.summary(emptyList(), emptyList())
        assertEquals(0f, none.fraction, 0f)
        assertEquals("Nothing due this month", BillsMath.progressCaption(none))

        val zeroes = BillsMath.summary(listOf(BigDecimal.ZERO), listOf(BigDecimal.ZERO))
        assertEquals(0.5f, zeroes.fraction, 0f)
    }

    @Test
    fun partPaymentMovesTheBarButNotTheCount() {
        // A 1000 bill with 250 paid: 750 left, 250 counted as paid money, still one bill to pay.
        val s = BillsMath.summary(listOf(bd(750)), emptyList(), partPaid = bd(250))
        assertEquals(bd(750), s.dueTotal)
        assertEquals(bd(250), s.paidTotal)
        assertEquals(1, s.dueCount)
        assertEquals(0, s.paidCount)
        assertEquals(0.25f, s.fraction, 0.0001f)
        assertFalse(s.allPaid)
        assertEquals("0 of 1 paid", BillsMath.progressCaption(s))
    }

    @Test
    fun markingOneBillPaidMovesTheBarForward() {
        val before = BillsMath.summary(listOf(bd(500), bd(500)), emptyList())
        val after = BillsMath.summary(listOf(bd(500)), listOf(bd(500)))
        assertEquals(0f, before.fraction, 0f)
        assertEquals(0.5f, after.fraction, 0f)
        assertEquals("1 of 2 paid", BillsMath.progressCaption(after))
    }

    @Test
    fun sortingPutsOverdueFirstThenBiggerAmountsThenNames() {
        data class B(val title: String, val date: LocalDate, val amount: Int)
        val items = listOf(
            B("Water", today.plusDays(2), 300), B("Rent", today.plusDays(2), 20000), B("Late", today.minusDays(3), 100),
            B("airtel", today.plusDays(2), 300), B("Early", today.plusDays(1), 50)
        )
        val sorted = BillsMath.sortDue(items, { it.date }, { bd(it.amount) }, { it.title }).map { it.title }
        assertEquals(listOf("Late", "Early", "Rent", "airtel", "Water"), sorted)
        val paid = BillsMath.sortPaid(listOf(B("b", today.minusDays(2), 1), B("A2", today, 1), B("a", today, 1)), { it.date }, { it.title }).map { it.title }
        assertEquals(listOf("a", "A2", "b"), paid)
    }
}

class CalendarPagingTest {
    private val today = LocalDate.of(2026, 11, 12)
    private fun entry(kind: CalendarKind, done: Boolean = false) = CalendarEntry(kind, "x", "", today, done = done)

    @Test
    fun directionFollowsTime() {
        assertEquals(PageDirection.NEXT, CalendarPaging.direction(YearMonth.of(2026, 11), YearMonth.of(2026, 12)))
        assertEquals(PageDirection.NEXT, CalendarPaging.direction(YearMonth.of(2026, 12), YearMonth.of(2027, 1)))
        assertEquals(PageDirection.PREVIOUS, CalendarPaging.direction(YearMonth.of(2026, 11), YearMonth.of(2026, 10)))
        assertEquals(PageDirection.NONE, CalendarPaging.direction(YearMonth.of(2026, 11), YearMonth.of(2026, 11)))
        assertEquals(1, CalendarPaging.enterSign(PageDirection.NEXT))
        assertEquals(-1, CalendarPaging.enterSign(PageDirection.PREVIOUS))
        assertEquals(0, CalendarPaging.enterSign(PageDirection.NONE))
        assertEquals(YearMonth.of(2027, 1), CalendarPaging.shifted(YearMonth.of(2026, 12), PageDirection.NEXT))
        assertEquals(YearMonth.of(2026, 12), CalendarPaging.shifted(YearMonth.of(2027, 1), PageDirection.PREVIOUS))
    }

    @Test
    fun slideNeverExceeds24dpOr30Percent() {
        assertEquals(24f, CalendarPaging.slideDp(360f), 0f)
        assertEquals(24f, CalendarPaging.slideDp(320f), 0f)
        assertEquals(15f, CalendarPaging.slideDp(50f), 0.0001f)
        assertEquals(0f, CalendarPaging.slideDp(-5f), 0f)
    }

    @Test
    fun swipeNeedsADeliberateDrag() {
        val threshold = CalendarPaging.swipeThreshold(360f)
        assertEquals(64.8f, threshold, 0.01f)
        assertEquals(48f, CalendarPaging.swipeThreshold(100f), 0f)
        assertEquals(PageDirection.NEXT, CalendarPaging.swipe(-threshold, threshold))
        assertEquals(PageDirection.PREVIOUS, CalendarPaging.swipe(threshold + 1, threshold))
        assertEquals(PageDirection.NONE, CalendarPaging.swipe(-threshold + 1, threshold))
        assertEquals(PageDirection.NONE, CalendarPaging.swipe(0f, threshold))
    }

    @Test
    fun dotsAreCappedAtThreeWithAPlus() {
        assertEquals(0, CalendarPaging.dots(0).shown)
        assertEquals(2, CalendarPaging.dots(2).shown)
        assertFalse(CalendarPaging.dots(3).more)
        assertEquals(3, CalendarPaging.dots(4).shown)
        assertTrue(CalendarPaging.dots(4).more)
    }

    @Test
    fun theDaySentenceNamesBillsAndOtherThings() {
        val tuesday = LocalDate.of(2026, 11, 10)
        val two = listOf(entry(CalendarKind.BILL), entry(CalendarKind.DEBIT), entry(CalendarKind.EVENT))
        assertEquals("Tuesday 10 November, 2 bills due, 1 other item", CalendarPaging.spoken(tuesday, today, two, Locale.ENGLISH))
        assertEquals("Thursday 12 November, today, 1 bill due", CalendarPaging.spoken(today, today, listOf(entry(CalendarKind.BILL)), Locale.ENGLISH))
        assertEquals("Friday 13 November, nothing planned", CalendarPaging.spoken(today.plusDays(1), today, emptyList(), Locale.ENGLISH))
        assertEquals(0, CalendarPaging.billsDue(listOf(entry(CalendarKind.BILL, done = true))))
    }
}
