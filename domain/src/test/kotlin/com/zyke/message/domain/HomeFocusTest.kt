package com.zyke.message.domain

import com.zyke.message.domain.home.Focus
import com.zyke.message.domain.home.FocusBill
import com.zyke.message.domain.home.FocusCode
import com.zyke.message.domain.home.FocusTrip
import com.zyke.message.domain.home.HomeFocus
import com.zyke.message.domain.home.HomeLayout
import com.zyke.message.domain.home.HomeSection
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFocusTest {
    private val today = LocalDate.of(2026, 11, 23)
    private val now = 1_000_000_000L
    private fun bill(key: String, days: Long, amount: Int = 100) = FocusBill(key, today.plusDays(days), BigDecimal(amount))
    private fun code(id: Long, agoMs: Long) = FocusCode(id, now - agoMs)

    @Test fun nothingToShowIsClear() {
        val pick = HomeFocus.pick(now, today)
        assertEquals(Focus.Clear, pick.focus)
        assertEquals(0, pick.more)
        assertFalse(pick.hasMore)
    }

    @Test fun aTripTodayBeatsEverything() {
        val pick = HomeFocus.pick(now, today, trips = listOf(FocusTrip("f1", false, 5)), codes = listOf(code(1, 1000)), bills = listOf(bill("b", 0)))
        assertEquals(Focus.Trip(FocusTrip("f1", false, 5)), pick.focus)
        assertEquals(2, pick.more) // the code and the bill
    }

    @Test fun theEarliestTripWinsAndTiesGoByKey() {
        val a = FocusTrip("a", true, 10)
        val b = FocusTrip("b", false, 10)
        val c = FocusTrip("c", false, 3)
        assertEquals(Focus.Trip(c), HomeFocus.pick(now, today, trips = listOf(a, b, c)).focus)
        assertEquals(Focus.Trip(a), HomeFocus.pick(now, today, trips = listOf(b, a)).focus)
    }

    @Test fun tripsOfTheOtherKindCountAsMore() {
        val pick = HomeFocus.pick(now, today, trips = listOf(FocusTrip("f", false, 1), FocusTrip("t", true, 2), FocusTrip("f2", false, 3)))
        assertEquals(1, pick.more)
    }

    @Test fun aFreshCodeBeatsBills() {
        val pick = HomeFocus.pick(now, today, codes = listOf(code(1, 60_000), code(2, 5_000)), bills = listOf(bill("b", 0), bill("c", 2)))
        val focus = pick.focus as Focus.Code
        assertEquals(2L, focus.newest.messageId)
        assertEquals(2, focus.count)
        assertEquals(2, pick.more)
    }

    @Test fun anExpiredOrFutureCodeIsIgnored() {
        val old = code(1, HomeFocus.CODE_LIFE_MS)
        val future = FocusCode(2, now + 5)
        assertEquals(Focus.Clear, HomeFocus.pick(now, today, codes = listOf(old, future)).focus)
        assertTrue(HomeFocus.pick(now, today, codes = listOf(code(3, HomeFocus.CODE_LIFE_MS - 1))).focus is Focus.Code)
    }

    @Test fun aBillDueTodayOrOverdueComesBeforeASoonerOne() {
        val pick = HomeFocus.pick(now, today, bills = listOf(bill("soon", 2), bill("today", 0), bill("late", -2)))
        val focus = pick.focus as Focus.BillDue
        assertEquals("late", focus.bill.key)
        assertEquals(2, focus.daysLate)
        assertEquals(2, pick.more)
    }

    @Test fun dueTodayHasNoDaysLate() {
        val focus = HomeFocus.pick(now, today, bills = listOf(bill("t", 0))).focus as Focus.BillDue
        assertEquals(0, focus.daysLate)
    }

    @Test fun theNextBillWithinThreeDaysIsSoon() {
        val focus = HomeFocus.pick(now, today, bills = listOf(bill("x", 3), bill("y", 1))).focus as Focus.BillSoon
        assertEquals("y", focus.bill.key)
        assertEquals(1, focus.inDays)
    }

    @Test fun aBillFourDaysAwayIsCalm() {
        assertEquals(Focus.Clear, HomeFocus.pick(now, today, bills = listOf(bill("far", 4))).focus)
    }

    @Test fun sameDayBillsGoByAmountThenKey() {
        assertEquals("big", (HomeFocus.pick(now, today, bills = listOf(bill("a", 1, 10), bill("big", 1, 500))).focus as Focus.BillSoon).bill.key)
        assertEquals("a", (HomeFocus.pick(now, today, bills = listOf(bill("b", 1, 10), bill("a", 1, 10))).focus as Focus.BillSoon).bill.key)
    }

    @Test fun calmLayoutDefaults() {
        val layout = HomeLayout.CALM
        assertTrue(layout.isCalm)
        for (s in HomeSection.entries) assertEquals(s.id, s.defaultOn, layout.shows(s))
        assertTrue(layout.shows(HomeSection.BUDGET) && layout.shows(HomeSection.ACTIVITY) && layout.shows(HomeSection.DECK))
        assertFalse(layout.shows(HomeSection.MARKET) || layout.shows(HomeSection.GOALS) || layout.shows(HomeSection.SHORTCUTS) || layout.shows(HomeSection.QUICK_ADDS))
    }

    @Test fun layoutChangesResetAndNormalise() {
        val changed = HomeLayout.CALM.with(HomeSection.GOALS, true).with(HomeSection.DECK, false)
        assertTrue(changed.shows(HomeSection.GOALS))
        assertFalse(changed.shows(HomeSection.DECK))
        assertFalse(changed.isCalm)
        // Putting a choice back to its default drops it, so equal meanings are equal values.
        assertEquals(HomeLayout.CALM, changed.with(HomeSection.GOALS, false).with(HomeSection.DECK, true))
        assertEquals(HomeLayout.CALM, changed.reset())
        // Unknown ids and redundant entries fall away.
        val messy = HomeLayout(mapOf("nonsense" to true, "deck" to true, "goals" to true))
        assertEquals(mapOf("goals" to true), messy.normalized().choices)
        assertTrue(messy.shows(HomeSection.GOALS))
    }

    @Test fun everySectionHasAUniqueIdAndWords() {
        assertEquals(HomeSection.entries.size, HomeSection.entries.map { it.id }.toSet().size)
        HomeSection.entries.forEach { assertTrue(it.title.isNotBlank() && it.summary.isNotBlank()); assertEquals(it, HomeSection.byId(it.id)) }
    }
}
