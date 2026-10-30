package com.zyke.message.domain

import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.QuoteParser
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.markets.WatchSymbol
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketsTest {
    private val reply = """{"chart":{"result":[{"meta":{"currency":"INR","symbol":"RELIANCE.NS","exchangeName":"NSI","regularMarketPrice":2950.55,"regularMarketTime":1793000000,"chartPreviousClose":2900.0,"previousClose":2900.0}}],"error":null}}"""

    @Test
    fun readsPriceAndPreviousClose() {
        val quote = QuoteParser.parse("RELIANCE.NS", reply)!!
        assertEquals(BigDecimal("2950.55"), quote.price)
        assertEquals(BigDecimal("2900.0"), quote.previousClose)
        assertEquals("INR", quote.currency)
        assertEquals(1793000000L, quote.tradedAt)
    }

    @Test
    fun anErrorReplyIsNoQuote() {
        assertNull(QuoteParser.parse("NOPE.NS", """{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}"""))
    }

    @Test
    fun bareNamesMeanTheNse_andTickersThatSayWhereKeepTheirs() {
        assertEquals("RELIANCE.NS", MarketSymbols.normalise(" reliance "))
        assertEquals("TCS.BO", MarketSymbols.normalise("tcs.bo"))
        assertEquals("^NSEI", MarketSymbols.normalise("^nsei"))
        assertEquals("EURINR=X", MarketSymbols.normalise("eurinr=x"))
        assertEquals("", MarketSymbols.normalise("  "))
        assertEquals("RELIANCE", MarketSymbols.defaultLabel("RELIANCE.NS"))
        assertEquals("NSEI", MarketSymbols.defaultLabel("^NSEI"))
    }

    @Test
    fun goldIsPricedInRupeesForTenGrams() {
        // 2,400 dollars an ounce at 84 rupees: 2400 * 84 / 31.1035 * 10 = 64,816.
        assertEquals(BigDecimal("64816"), MarketSymbols.goldPerTenGrams(BigDecimal("2400"), BigDecimal("84")))
    }

    @Test
    fun rowsShowTheDollarAndGoldFirst_thenTheListInItsOrder_withChange() {
        val settings = WatchSettings(true, listOf(WatchSymbol("^NSEI", "Nifty 50"), WatchSymbol("RELIANCE.NS", "Reliance")))
        val quotes = mapOf(
            "USDINR=X" to Quote("USDINR=X", BigDecimal("84.00"), BigDecimal("83.50"), "INR"),
            "GC=F" to Quote("GC=F", BigDecimal("2400"), BigDecimal("2380"), "USD"),
            "^NSEI" to Quote("^NSEI", BigDecimal("24000"), BigDecimal("24240"), "INR"),
            "RELIANCE.NS" to Quote("RELIANCE.NS", BigDecimal("2950.55"), BigDecimal("2900"), "INR")
        )
        val rows = MarketSymbols.rows(settings, quotes)

        assertEquals(listOf("USD / INR", "Gold", "Nifty 50", "Reliance"), rows.map { it.label })
        assertEquals(listOf(MarketKind.CURRENCY, MarketKind.COMMODITY, MarketKind.INDEX, MarketKind.STOCK), rows.map { it.kind })
        assertEquals(BigDecimal("0.60"), rows[0].changePercent)
        assertTrue(rows[1].isUp)
        assertTrue(rows[2].isDown)
        assertEquals("", rows[2].currency)
        assertEquals(BigDecimal("1.74"), rows[3].changePercent)
    }

    @Test
    fun whatIsNotFetchedYetIsLeftOut_andOnlyWhatIsWantedIsAskedFor() {
        val settings = WatchSettings(true, listOf(WatchSymbol("TCS.NS", "TCS")), showDollar = false, showGold = false)
        assertEquals(listOf("TCS.NS"), MarketSymbols.needed(settings))
        assertEquals(emptyList<Any>(), MarketSymbols.rows(settings, emptyMap()))
        assertEquals(listOf("TCS.NS", "USDINR=X", "GC=F"), MarketSymbols.needed(settings.copy(showGold = true)))
    }
}
