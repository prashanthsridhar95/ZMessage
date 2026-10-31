package com.zyke.message.domain.wear

import com.zyke.message.domain.budget.DailyBudgetStatus
import com.zyke.message.domain.forecast.OutflowItem
import com.zyke.message.domain.forecast.OutflowSource
import com.zyke.message.domain.forecast.UpcomingOutflow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSnapshotTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-31T06:00:00Z")
    private val today = LocalDate.of(2026, 10, 31)

    private fun bd(v: String) = BigDecimal(v)
    private fun item(name: String, amount: String, days: Long, source: OutflowSource = OutflowSource.CARD_BILL) =
        OutflowItem(name, bd(amount), today.plusDays(days), source)

    private val outflow = UpcomingOutflow(
        today, today.plusDays(6),
        listOf(
            item("Rent SIP", "5000", 1, OutflowSource.PLAN),
            item("Home loan EMI", "21500.50", 3, OutflowSource.EMI),
            item("HDFC card", "8200", 1),
            item("Netflix", "649", 1, OutflowSource.AUTO_DEBIT),
            item("Phone bill", "399", 5, OutflowSource.AUTO_DEBIT),
            item("Old", "10", -1)
        )
    )

    private fun build(hide: Boolean = false, daily: DailyBudgetStatus? = DailyBudgetStatus(bd("1500"), bd("420.40"), 0, 0)) =
        WatchSnapshot.build(now, zone, hide, daily, bd("30000"), bd("24000"), outflow, 4, bd("98765.90"))

    @Test
    fun figuresAreWholeRupees_roundedTheCautiousWay() {
        val s = build()
        assertEquals(1079L, s.todayLeft) // 1079.60 floored
        assertEquals(30000L, s.monthSpent)
        assertEquals(24000L, s.lastMonthSpent)
        assertEquals(25, s.monthDeltaPercent)
        assertEquals(98765L, s.balance)
        assertEquals(4, s.reviewCount)
        assertEquals("2026-10-31", s.day)
        assertFalse(s.masked)
    }

    @Test
    fun overBudgetIsNegative() {
        assertEquals(-100L, build(daily = DailyBudgetStatus(bd("1000"), bd("1100"), 0, 0)).todayLeft)
        assertNull(build(daily = null).todayLeft)
    }

    @Test
    fun nextThreeBillsOrEmis_soonestFirst_biggestFirstOnTheSameDay_noPlanItemsNoPast() {
        val bills = build().bills
        assertEquals(listOf("HDFC card", "Netflix", "Home loan EMI"), bills.map { it.name })
        assertEquals(listOf(1, 1, 3), bills.map { it.inDays })
        assertEquals(listOf(8200L, 649L, 21501L), bills.map { it.rupees })
    }

    @Test
    fun hiddenAmounts_leaveNoMoneyFigureAtAll() {
        val s = build(hide = true)
        assertTrue(s.masked)
        assertNull(s.todayLeft); assertNull(s.monthSpent); assertNull(s.lastMonthSpent)
        assertNull(s.monthDeltaPercent); assertNull(s.balance)
        assertTrue(s.bills.all { it.rupees == null })
        assertEquals(3, s.bills.size) // names and days are still useful
        assertEquals(4, s.reviewCount)
        val wire = s.toMap()
        assertTrue(wire.keys.none { it in setOf("left", "spent", "prev", "delta", "bal") || (it.startsWith("b") && it.endsWith("a") && it != "bills") })
    }

    @Test
    fun noComparisonWhenLastMonthIsEmpty() {
        val s = WatchSnapshot.build(now, zone, false, null, bd("100"), BigDecimal.ZERO, null, 0, null)
        assertNull(s.monthDeltaPercent)
        assertEquals(emptyList<WatchBill>(), s.bills)
        assertNull(s.balance)
    }

    @Test
    fun longNamesAreCut() {
        val o = UpcomingOutflow(today, today, listOf(item("A".repeat(60), "10", 0)))
        val s = WatchSnapshot.build(now, zone, false, null, null, null, o, 0, null)
        assertEquals(WatchSnapshot.NAME_MAX, s.bills.single().name.length)
    }

    @Test
    fun theWireFormRoundTrips_andHoldsOnlyTheKnownKeys() {
        val s = build()
        val wire = s.toMap()
        assertEquals(s, WatchSnapshot.fromMap(wire))
        assertEquals(setOf("v", "at", "day", "masked", "review", "left", "spent", "prev", "delta", "bal", "bills", "b0n", "b0d", "b0a", "b1n", "b1d", "b1a", "b2n", "b2d", "b2a"), wire.keys)
        val hidden = build(hide = true)
        assertEquals(hidden, WatchSnapshot.fromMap(hidden.toMap()))
    }

    @Test
    fun theWireFormIsStable() {
        val s = WatchSnapshot(1000L, "2026-10-31", false, 50, 10, null, null, listOf(WatchBill("Rent", 900, 2)), 1, null)
        assertEquals(
            mapOf("v" to "1", "at" to "1000", "day" to "2026-10-31", "masked" to "false", "review" to "1", "left" to "50", "spent" to "10", "bills" to "1", "b0n" to "Rent", "b0d" to "2", "b0a" to "900"),
            s.toMap()
        )
    }

    @Test
    fun unknownOrBrokenInputIsRefused() {
        assertNull(WatchSnapshot.fromMap(emptyMap()))
        assertNull(WatchSnapshot.fromMap(build().toMap() + ("v" to "2")))
        assertNull(WatchSnapshot.fromMap(build().toMap() - "b0n"))
        assertNotNull(WatchSnapshot.fromMap(build().toMap() - "left"))
    }

    @Test
    fun staleness() {
        assertTrue(build().isFromToday(today))
        assertFalse(build().isFromToday(today.plusDays(1)))
    }

    @Test
    fun pushOnlyWhenSomethingChangedOrTheCopyIsOld() {
        val a = build()
        assertTrue(WatchSnapshot.shouldPush(null, a))
        assertFalse(WatchSnapshot.shouldPush(a, a.copy(updatedAt = a.updatedAt + 60_000)))
        assertTrue(WatchSnapshot.shouldPush(a, a.copy(updatedAt = a.updatedAt + 60_000, reviewCount = 5)))
        assertTrue(WatchSnapshot.shouldPush(a, a.copy(updatedAt = a.updatedAt + WatchSnapshot.REFRESH_MS)))
    }
}
