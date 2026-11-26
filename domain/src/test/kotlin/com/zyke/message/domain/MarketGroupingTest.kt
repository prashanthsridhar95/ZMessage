package com.zyke.message.domain

import com.zyke.message.domain.markets.CloseDecision
import com.zyke.message.domain.markets.Market
import com.zyke.message.domain.markets.MarketCalendar
import com.zyke.message.domain.markets.MarketCloseSummary
import com.zyke.message.domain.markets.MarketGrouping
import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketOf
import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.SessionStatus
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.markets.WatchSymbol
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketGroupingTest {
    private val ny = ZoneId.of("America/New_York")
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Instant = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant()

    private fun row(label: String, symbol: String, exchange: String = "", kind: MarketKind = MarketKind.STOCK, pct: String? = "1.00") =
        MarketRow(label, symbol, kind, BigDecimal("100"), pct?.let { BigDecimal(it) }, pct?.let { BigDecimal(it) }, "", exchange, symbol)

    @Test fun marketFromSymbolAndExchange() {
        assertEquals(Market.INDIA, MarketOf.of("RELIANCE.NS"))
        assertEquals(Market.INDIA, MarketOf.of("TCS.BO"))
        assertEquals(Market.INDIA, MarketOf.of("^NSEI"))
        assertEquals(Market.UNITED_STATES, MarketOf.of("AAPL", "NASDAQ"))
        assertEquals(Market.UNITED_STATES, MarketOf.of("AAPL"))
        assertEquals(Market.UNITED_STATES, MarketOf.of("^GSPC"))
        assertEquals(Market.UNITED_KINGDOM, MarketOf.of("VOD.L"))
        assertEquals(Market.JAPAN, MarketOf.of("7203.T"))
        assertEquals(Market.OTHER, MarketOf.of("USDINR=X"))
        assertEquals(Market.OTHER, MarketOf.of("GC=F"))
        assertEquals(Market.OTHER, MarketOf.of("BTC-USD"))
    }

    @Test fun flagsAreRegionalIndicatorPairs() {
        assertEquals("🇮🇳", Market.INDIA.flag)
        assertEquals("🇺🇸", Market.UNITED_STATES.flag)
        assertEquals("", Market.OTHER.flag)
    }

    @Test fun groupsKeepFirstSeenOrderOtherLastAndTheIndexHeadsItsGroup() {
        val rows = listOf(
            row("USD / INR", "USDINR=X", kind = MarketKind.CURRENCY), row("Apple", "AAPL", "NASDAQ"), row("Reliance", "RELIANCE.NS", "NSE"),
            row("TCS", "TCS.NS", "NSE"), row("S&P 500", "^GSPC", kind = MarketKind.INDEX, pct = "-0.21"), row("Nifty 50", "^NSEI", kind = MarketKind.INDEX)
        )
        val listed = rows.filter { it.kind != MarketKind.INDEX }
        val groups = MarketGrouping.group(listed, indexSource = rows)
        assertEquals(listOf(Market.UNITED_STATES, Market.INDIA, Market.OTHER), groups.map { it.market })
        assertEquals(listOf("Reliance", "TCS"), groups[1].rows.map { it.label })
        assertEquals("^GSPC", groups[0].index?.symbol)
        assertEquals("^NSEI", groups[1].index?.symbol)
        assertNull(groups[2].index)
    }

    @Test fun defaultIsOnOnlyWithMoreThanOneMarketAndHiddenOtherwise() {
        val india = listOf(row("Reliance", "RELIANCE.NS"), row("TCS", "TCS.NS"), row("USD / INR", "USDINR=X", kind = MarketKind.CURRENCY))
        assertFalse(MarketGrouping.offered(india))
        assertFalse("a lone market is never grouped, even if the saved choice says so", MarketGrouping.shown(true, india))
        val two = india + row("Apple", "AAPL", "NASDAQ")
        assertTrue(MarketGrouping.offered(two))
        assertTrue("no choice yet: on", MarketGrouping.shown(null, two))
        assertFalse("the person's off sticks", MarketGrouping.shown(false, two))
        assertTrue(MarketGrouping.shown(true, two))
    }

    @Test fun regularHoursFollowTheMarketsOwnClockAndWeekdays() {
        // Friday 6 Nov 2026: India open at 10:00 IST, closed at 16:00 IST; the next open is Monday 9:15.
        assertTrue(MarketCalendar.status(Market.INDIA, at(ist, 2026, 11, 6, 10, 0)) is SessionStatus.Open)
        val closed = MarketCalendar.status(Market.INDIA, at(ist, 2026, 11, 6, 16, 0)) as SessionStatus.Closed
        assertEquals(at(ist, 2026, 11, 9, 9, 15), closed.opensAt.toInstant())
        // Saturday is closed; before the open the same day opens today.
        assertTrue(MarketCalendar.status(Market.UNITED_STATES, at(ny, 2026, 11, 7, 11, 0)) is SessionStatus.Closed)
        val early = MarketCalendar.status(Market.UNITED_STATES, at(ny, 2026, 11, 6, 8, 0)) as SessionStatus.Closed
        assertEquals(at(ny, 2026, 11, 6, 9, 30), early.opensAt.toInstant())
        assertEquals("Open · closes 4:00 PM ET", MarketCalendar.hint(Market.UNITED_STATES, at(ny, 2026, 11, 6, 11, 0)))
        assertEquals("Closed · opens Mon 9:15 AM IST", MarketCalendar.hint(Market.INDIA, at(ist, 2026, 11, 6, 16, 0)))
        assertEquals("Closed · opens 9:30 AM ET", MarketCalendar.hint(Market.UNITED_STATES, at(ny, 2026, 11, 6, 8, 0)))
        assertNull(MarketCalendar.hint(Market.OTHER, Instant.now()))
    }

    @Test fun usSessionFollowsSummerTime() {
        // 15:30 UTC is 11:30 EDT in July (open) and 10:30 EST in January (open); 20:30 UTC is 16:30 EDT / 15:30 EST.
        assertTrue(MarketCalendar.status(Market.UNITED_STATES, Instant.parse("2026-07-14T20:30:00Z")) is SessionStatus.Closed)
        assertTrue(MarketCalendar.status(Market.UNITED_STATES, Instant.parse("2026-01-14T20:30:00Z")) is SessionStatus.Open)
    }

    // ---- close summary for several markets ----

    private val friday = LocalDate.of(2026, 11, 6)
    private fun q(symbol: String, price: String, before: String?, currency: String, tradedAt: Instant?) =
        Quote(symbol, BigDecimal(price), before?.let { BigDecimal(it) }, currency, tradedAt?.epochSecond)

    private val watched = listOf(
        WatchSymbol("AAPL", "Apple", "NASDAQ"), WatchSymbol("TSLA", "Tesla", "NASDAQ"), WatchSymbol("RELIANCE.NS", "Reliance", "NSE"),
        WatchSymbol("^NSEI", "Nifty 50"), WatchSymbol("USDINR=X", "USD / INR")
    )

    @Test fun stocksAreSplitByMarketAndIndicesCurrenciesAreLeftOut() {
        assertEquals(listOf("AAPL", "TSLA"), MarketCloseSummary.stocksOf(Market.UNITED_STATES, watched).map { it.symbol })
        assertEquals(listOf("RELIANCE.NS"), MarketCloseSummary.stocksOf(Market.INDIA, watched).map { it.symbol })
        assertEquals(listOf("^GSPC", "^IXIC", "^DJI", "AAPL", "TSLA"), MarketCloseSummary.tickersFor(Market.UNITED_STATES, watched))
    }

    @Test fun usSummaryListsTopMoversFirstWithSignsAndDollars() {
        val t = at(ny, 2026, 11, 6, 16, 0)
        val quotes = listOf(
            q("^GSPC", "6012.30", "5987.20", "", t), q("^IXIC", "19402.10", "19284.00", "", t), q("^DJI", "44120.50", "44156.00", "", t),
            q("AAPL", "231.40", "228.70", "USD", t), q("TSLA", "402.15", "407.25", "USD", t)
        )
        val text = MarketCloseSummary.build(quotes, Market.UNITED_STATES, watched)!!
        assertEquals("United States market closed · S&P 500 6,012.30 ▲ 0.42%", text.title)
        assertEquals("NASDAQ 19,402.10 ▲ 0.61% · Dow 44,120.50 ▼ 0.08% · 1 up, 1 down", text.text)
        // TSLA moved -1.25%, AAPL +1.18%: the bigger mover leads whichever way it went.
        assertTrue(text.body, text.body.contains("Your stocks\nTesla  $402.15  ▼ 1.25%\nApple  $231.40  ▲ 1.18%"))
        assertFalse(text.body.contains("Reliance"))
    }

    @Test fun indiaSummaryUsesRupeesAndCapsTheStockLines() {
        val t = at(ist, 2026, 11, 6, 15, 30)
        val many = (1..11).map { WatchSymbol("S$it.NS", "Stock $it", "NSE") }
        val quotes = listOf(q("^NSEI", "24386.10", "24093.75", "", t)) +
            many.mapIndexed { i, w -> q(w.symbol, "${1000 + i}", "1000", "INR", t) }
        val text = MarketCloseSummary.build(quotes, Market.INDIA, many)!!
        val lines = text.body.lines()
        assertEquals(8, lines.count { it.startsWith("Stock ") })
        assertEquals("+3 more", lines.last())
        // Biggest mover (Stock 11, +1.00%) first, in rupees.
        assertEquals("Stock 11  ₹1,010.00  ▲ 1.00%", lines[lines.indexOf("Your stocks") + 1])
    }

    @Test fun staleStocksNeverAppearAndNothingFreshMeansNoSummary() {
        val today = at(ny, 2026, 11, 6, 16, 0)
        val old = at(ny, 2026, 11, 5, 16, 0)
        val quotes = listOf(q("^GSPC", "6000", "5990", "", today), q("AAPL", "231.40", "228.70", "USD", old))
        val d = MarketCloseSummary.decide(at(ny, 2026, 11, 6, 16, 20), quotes, retried = false, market = Market.UNITED_STATES, watched = watched) as CloseDecision.Send
        assertFalse(d.summary.body.contains("Apple"))
        // Holiday: everything is from yesterday.
        val holiday = listOf(q("^GSPC", "6000", "5990", "", old), q("AAPL", "231.40", "228.70", "USD", old))
        assertEquals(CloseDecision.RetryLater, MarketCloseSummary.decide(at(ny, 2026, 11, 6, 16, 20), holiday, false, Market.UNITED_STATES, watched))
        assertEquals(CloseDecision.Skip, MarketCloseSummary.decide(at(ny, 2026, 11, 6, 16, 40), holiday, true, Market.UNITED_STATES, watched))
        // Saturday in New York.
        assertEquals(CloseDecision.Skip, MarketCloseSummary.decide(at(ny, 2026, 11, 7, 16, 20), quotes, false, Market.UNITED_STATES, watched))
    }

    @Test fun nothingWatchedStillGivesTheIndexAndNoStocksSection() {
        val t = at(ny, 2026, 11, 6, 16, 0)
        val text = MarketCloseSummary.build(listOf(q("^GSPC", "6012.30", "5987.20", "", t)), Market.UNITED_STATES, emptyList())!!
        assertFalse(text.body.contains("Your stocks"))
        assertEquals("NYSE and NASDAQ closed for the day", text.text)
    }

    @Test fun offsetMovesTheFireTimeAndWeekendsAreSkipped() {
        val fri = at(ny, 2026, 11, 6, 17, 0)
        assertEquals(at(ny, 2026, 11, 9, 16, 30), MarketCloseSummary.nextFire(fri, Market.UNITED_STATES, 30))
        assertNotNull(MarketCalendar.nextCloseSummary(Market.INDIA, 5, Instant.now()))
        assertNull(MarketCalendar.nextCloseSummary(Market.OTHER, 5, Instant.now()))
    }

    @Test fun settingsSchedulesFollowEachSwitchAndTheWatchBeingOn() {
        val s = WatchSettings(enabled = true, marketCloseSummary = true, marketCloseUs = true, closeOffsetMinutes = 30)
        assertEquals(setOf(Market.INDIA, Market.UNITED_STATES) to 30, s.closeSchedule)
        assertEquals(setOf(Market.UNITED_STATES) to 15, WatchSettings(enabled = true, marketCloseUs = true).closeSchedule)
        assertTrue(WatchSettings().closeSchedule.first.isEmpty())
        assertFalse(WatchSettings(enabled = false, marketCloseUs = true).closeActive(Market.UNITED_STATES))
        assertEquals(MarketSymbols.NIFTY, MarketCloseSummary.symbols(Market.INDIA).first())
        assertEquals(LocalTime.of(15, 30), Market.INDIA.closes)
    }
}
