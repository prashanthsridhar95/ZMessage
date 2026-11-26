package com.zyke.message.domain

import com.zyke.message.domain.markets.CloseDecision
import com.zyke.message.domain.markets.MarketCloseSummary
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.WatchSettings
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCloseSummaryTest {
    private val zone = MarketCloseSummary.ZONE
    // Friday 6 November 2026 (a trading weekday).
    private val friday = LocalDate.of(2026, 11, 6)

    private fun at(day: LocalDate, h: Int, m: Int): Instant = ZonedDateTime.of(day, LocalTime.of(h, m), zone).toInstant()

    private fun quote(symbol: String, price: String, before: String?, tradedAt: Instant? = at(friday, 15, 30)) =
        Quote(symbol, BigDecimal(price), before?.let { BigDecimal(it) }, "INR", tradedAt?.epochSecond)

    private val nifty = quote(MarketSymbols.NIFTY, "24386.10", "24093.75")
    private val sensex = quote(MarketSymbols.SENSEX, "79876.50", "80124.00")

    @Test fun titleLeadsWithNiftyAndBodyListsEachIndex() {
        val text = MarketCloseSummary.build(listOf(sensex, nifty))!!
        assertEquals("India market closed · Nifty 50 24,386.10 ▲ 1.21%", text.title)
        assertEquals("Sensex 79,876.50 ▼ 0.31%", text.text)
        assertEquals("Nifty 50  24,386.10  ▲ 292.35 (1.21%)\nSensex  79,876.50  ▼ 247.50 (0.31%)", text.body)
    }

    @Test fun percentRoundsHalfUpToTwoPlaces() {
        // 0.125% of 1000 -> 1001.25 over 1000: 0.125 -> 0.13
        val up = MarketCloseSummary.build(listOf(quote(MarketSymbols.NIFTY, "1001.25", "1000")))!!
        assertTrue(up.title, up.title.endsWith("▲ 0.13%"))
        val down = MarketCloseSummary.build(listOf(quote(MarketSymbols.NIFTY, "998.75", "1000")))!!
        assertTrue(down.title, down.title.endsWith("▼ 0.13%"))
    }

    @Test fun unchangedHasNoArrow() {
        val text = MarketCloseSummary.build(listOf(quote(MarketSymbols.NIFTY, "24000", "24000")))!!
        assertEquals("India market closed · Nifty 50 24,000.00 0.00%", text.title)
    }

    @Test fun missingPreviousCloseShowsTheLevelOnly() {
        val text = MarketCloseSummary.build(listOf(quote(MarketSymbols.NIFTY, "24000", null)))!!
        assertEquals("India market closed · Nifty 50 24,000.00", text.title)
        assertEquals("Nifty 50  24,000.00", text.body)
    }

    @Test fun missingIndexIsLeftOutNeverZero() {
        val text = MarketCloseSummary.build(listOf(sensex))!!
        assertTrue(text.title.startsWith("India market closed · Sensex 79,876.50"))
        assertFalse("Nifty" in text.body)
        assertNull(MarketCloseSummary.build(emptyList()))
        assertNull(MarketCloseSummary.build(listOf(quote("RELIANCE.NS", "3000", "2990"))))
    }

    @Test fun weekendIsSkippedWithoutLooking() {
        val saturday = at(LocalDate.of(2026, 11, 7), 15, 45)
        assertEquals(CloseDecision.Skip, MarketCloseSummary.decide(saturday, listOf(nifty), retried = false))
    }

    @Test fun freshCloseIsSent() {
        val d = MarketCloseSummary.decide(at(friday, 15, 46), listOf(nifty, sensex), retried = false)
        assertTrue(d is CloseDecision.Send)
    }

    @Test fun yesterdaysFiguresAreNeverSent_holidayOrStaleData() {
        val thursday = friday.minusDays(1)
        val stale = listOf(quote(MarketSymbols.NIFTY, "24000", "23900", at(thursday, 15, 30)), quote(MarketSymbols.SENSEX, "79000", "78900", at(thursday, 15, 30)))
        assertEquals(CloseDecision.RetryLater, MarketCloseSummary.decide(at(friday, 15, 46), stale, retried = false))
        assertEquals(CloseDecision.Skip, MarketCloseSummary.decide(at(friday, 16, 6), stale, retried = true))
    }

    @Test fun offlineRetriesOnceThenSkips() {
        assertEquals(CloseDecision.RetryLater, MarketCloseSummary.decide(at(friday, 15, 46), emptyList(), retried = false))
        assertEquals(CloseDecision.Skip, MarketCloseSummary.decide(at(friday, 16, 6), emptyList(), retried = true))
    }

    @Test fun aTradeBeforeTheCloseOrWithoutATimeIsNotAClose() {
        val midday = quote(MarketSymbols.NIFTY, "24000", "23900", at(friday, 12, 0))
        val undated = quote(MarketSymbols.SENSEX, "79000", "78900", null)
        assertEquals(CloseDecision.RetryLater, MarketCloseSummary.decide(at(friday, 15, 46), listOf(midday, undated), retried = false))
    }

    @Test fun onlyTodaysIndicesAreShownWhenOneIsStale() {
        val staleSensex = quote(MarketSymbols.SENSEX, "79000", "78900", at(friday.minusDays(1), 15, 30))
        val d = MarketCloseSummary.decide(at(friday, 15, 46), listOf(nifty, staleSensex), retried = false) as CloseDecision.Send
        assertFalse("Sensex" in d.summary.body)
    }

    @Test fun nextFireIsQuarterToFourIstOnTheNextWeekday() {
        assertEquals(at(friday, 15, 45), MarketCloseSummary.nextFire(at(friday, 9, 0)))
        // Already past today's: Friday evening goes to Monday; Saturday goes to Monday.
        val monday = LocalDate.of(2026, 11, 9)
        assertEquals(at(monday, 15, 45), MarketCloseSummary.nextFire(at(friday, 15, 45)))
        assertEquals(at(monday, 15, 45), MarketCloseSummary.nextFire(at(LocalDate.of(2026, 11, 7), 10, 0)))
        assertEquals(at(friday.plusDays(0), 15, 45), MarketCloseSummary.nextFire(at(friday, 15, 44)))
    }

    @Test fun usSummaryUsesNewYorkTimeAndSummerTime() {
        val ny = java.time.ZoneId.of("America/New_York")
        // 6 Nov 2026 is standard time (EST, UTC-5); 14 Jul 2026 is summer time (EDT, UTC-4). 16:00 + 15 minutes either way.
        val winter = ZonedDateTime.of(friday, LocalTime.of(16, 15), ny).toInstant()
        assertEquals(winter, MarketCloseSummary.nextFire(ZonedDateTime.of(friday, LocalTime.of(9, 0), ny).toInstant(), com.zyke.message.domain.markets.Market.UNITED_STATES, 15))
        val july = LocalDate.of(2026, 7, 14)
        assertEquals(ZonedDateTime.of(july, LocalTime.of(16, 15), ny).toInstant(), MarketCloseSummary.nextFire(ZonedDateTime.of(july, LocalTime.of(9, 0), ny).toInstant(), com.zyke.message.domain.markets.Market.UNITED_STATES, 15))
    }

    @Test fun switchIsOffByDefaultAndNeedsTheMarketWatch() {
        assertFalse(WatchSettings().marketCloseSummary)
        assertFalse(WatchSettings().closeSummaryActive)
        assertFalse(WatchSettings(enabled = false, marketCloseSummary = true).closeSummaryActive)
        assertFalse(WatchSettings(enabled = true).closeSummaryActive)
        assertTrue(WatchSettings(enabled = true, marketCloseSummary = true).closeSummaryActive)
    }
}
