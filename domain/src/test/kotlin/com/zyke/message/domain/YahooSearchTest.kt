package com.zyke.message.domain

import com.zyke.message.domain.markets.CurrencySymbols
import com.zyke.message.domain.markets.MarketKind
import com.zyke.message.domain.markets.MarketNames
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.QuoteParser
import com.zyke.message.domain.markets.StockDirectory
import com.zyke.message.domain.markets.StockSuggestion
import com.zyke.message.domain.markets.SuggestionMerge
import com.zyke.message.domain.markets.SuggestionType
import com.zyke.message.domain.markets.WatchSettings
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.markets.YahooSearchParser
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YahooSearchTest {
    private val sample = """
        {"explains":[],"count":5,"quotes":[
          {"exchange":"NSI","shortname":"RELIANCE INDUSTRIES LTD","quoteType":"EQUITY","symbol":"RELIANCE.NS","index":"quotes","score":2000000,"typeDisp":"Equity","longname":"Reliance Industries Limited","exchDisp":"NSE","sector":"Energy","isYahooFinance":true},
          {"exchange":"NMS","shortname":"Apple Inc.","quoteType":"EQUITY","symbol":"AAPL","typeDisp":"Equity","longname":"Apple Inc.","exchDisp":"NASDAQ"},
          {"exchange":"LSE","shortname":"VODAFONE GROUP PLC","quoteType":"EQUITY","symbol":"VOD.L","longname":"Vodafone Group Plc","exchDisp":"London"},
          {"exchange":"SNP","shortname":"S&P 500","quoteType":"INDEX","symbol":"^GSPC","exchDisp":"S&P"},
          {"exchange":"CCC","shortname":"Bitcoin USD","quoteType":"CRYPTOCURRENCY","symbol":"BTC-USD","exchDisp":"CCC"},
          {"exchange":"CMX","shortname":"Gold Dec 26","quoteType":"FUTURE","symbol":"GC=F"},
          {"exchange":"CCY","shortname":"EUR/USD","quoteType":"CURRENCY","symbol":"EURUSD=X"},
          {"exchange":"PCX","shortname":"SPDR S&P 500","quoteType":"ETF","symbol":"SPY"},
          {"exchange":"NAS","shortname":"Some Fund","quoteType":"MUTUALFUND","symbol":"VFIAX"},
          {"shortname":"No symbol here","quoteType":"EQUITY"},
          {"symbol":"  ","quoteType":"EQUITY"}
        ],"news":[],"nav":[],"lists":[]}
    """.trimIndent()

    @Test
    fun readsSymbolsNamesExchangesAndTypes() {
        val result = YahooSearchParser.parse(sample)
        assertEquals(
            listOf("RELIANCE.NS", "AAPL", "VOD.L", "^GSPC", "BTC-USD", "GC=F", "EURUSD=X", "SPY", "VFIAX"),
            result.map { it.symbol }
        )
        val reliance = result[0]
        assertEquals("Reliance Industries Limited", reliance.name) // the shouted short name is replaced by the long one
        assertEquals("NSE", reliance.exchange)
        assertEquals(SuggestionType.STOCK, reliance.type)
        assertEquals("NASDAQ", result[1].exchange)
        assertEquals("LSE", result[2].exchange)
        assertEquals(
            listOf(SuggestionType.STOCK, SuggestionType.STOCK, SuggestionType.STOCK, SuggestionType.INDEX, SuggestionType.CRYPTO,
                SuggestionType.COMMODITY, SuggestionType.FX, SuggestionType.ETF, SuggestionType.FUND),
            result.map { it.type }
        )
        assertEquals("Reliance Industries", reliance.toWatchSymbol().label)
        assertEquals(WatchSymbol("RELIANCE.NS", "Reliance Industries", "NSE"), reliance.toWatchSymbol())
    }

    @Test
    fun missingFieldsFallBack() {
        val result = YahooSearchParser.parse("""{"quotes":[{"symbol":"7203.T"},{"symbol":"SAP.DE","longname":"SAP SE","quoteType":"SOMETHING NEW","exchDisp":"XETRA"}]}""")
        assertEquals(2, result.size)
        assertEquals(StockSuggestion("7203.T", "7203.T", "", SuggestionType.OTHER), result[0])
        assertEquals("SAP SE", result[1].name)
        assertEquals("XETRA", result[1].exchange)
        assertEquals(SuggestionType.OTHER, result[1].type)
        assertEquals("SAP", result[1].toWatchSymbol().label)
    }

    @Test
    fun emptyAndUnexpectedShapesGiveNothing() {
        assertTrue(YahooSearchParser.parse("""{"quotes":[]}""").isEmpty())
        assertTrue(YahooSearchParser.parse("""{"count":0}""").isEmpty())
        assertTrue(YahooSearchParser.parse("""{"quotes":{"symbol":"AAPL"}}""").isEmpty())
        assertTrue(YahooSearchParser.parse("""{"quotes":[1,"AAPL",null,[{"symbol":"X"}]]}""").isEmpty())
        assertTrue(YahooSearchParser.parse("""{"quotes":[{"symbol":42},{"symbol":null},{"symbol":"bad symbol!"}]}""").isEmpty())
        assertTrue(YahooSearchParser.parse("[]").isEmpty())
        assertTrue(YahooSearchParser.parse("null").isEmpty())
    }

    @Test
    fun garbageGivesNothing_andNeverThrows() {
        listOf("", "   ", "not json", "{", "{\"quotes\":[", "{\"quotes\":[{\"symbol\":\"AA", "<html>Too Many Requests</html>", "\u0000\u0001", "{'quotes':[]}",
            "[".repeat(5000), "{\"quotes\":[{\"symbol\":\"A\\u12\"}]}").forEach {
            assertTrue(it, YahooSearchParser.parse(it).isEmpty())
        }
    }

    @Test
    fun duplicatesAreDropped_andTheListIsCapped() {
        val many = (1..40).joinToString(",") { """{"symbol":"S$it","shortname":"Name $it","quoteType":"EQUITY"}""" }
        assertEquals(10, YahooSearchParser.parse("""{"quotes":[$many]}""").size)
        assertEquals(1, YahooSearchParser.parse("""{"quotes":[{"symbol":"AAPL"},{"symbol":"aapl"}]}""").size)
    }

    @Test
    fun unescapesTextInNames() {
        val result = YahooSearchParser.parse("""{"quotes":[{"symbol":"MM.NS","shortname":"Mahindra & Mahindra \"M&M\"","quoteType":"EQUITY"}]}""")
        assertEquals("Mahindra & Mahindra \"M&M\"", result.single().name)
    }

    @Test
    fun mergePutsTheServiceFirst_dropsDuplicatesAndWatchedOnes() {
        val service = listOf(StockSuggestion("TCS.NS", "TCS", "NSE", SuggestionType.STOCK), StockSuggestion("TCS.BO", "TCS", "BSE", SuggestionType.STOCK))
        val local = StockDirectory.search("tcs").map { SuggestionMerge.fromLocal(it) } +
            SuggestionMerge.fromLocal(StockDirectory.search("infosys").first())
        val merged = SuggestionMerge.merge(service, local, setOf("TCS.BO"))
        assertEquals(listOf("TCS.NS", "INFY.NS"), merged.map { it.symbol })
        assertEquals("NSE", merged.last().exchange)
        assertEquals(3, SuggestionMerge.merge(service, local, emptySet(), limit = 3).size)
    }

    @Test
    fun worldTickersAreValid() {
        listOf("RELIANCE.NS", "TCS.BO", "7203.T", "VOD.L", "SAP.DE", "0700.HK", "AAPL", "^GSPC", "^NSEI", "BTC-USD", "EURUSD=X", "GC=F", "M&M.NS", "BRK-B")
            .forEach { assertTrue(it, MarketSymbols.isValidSymbol(it)) }
        listOf("", "a b", "aapl", "AAPL!", "-X", ".NS", "A".repeat(31), "AA\nPL", "<script>").forEach { assertFalse(it, MarketSymbols.isValidSymbol(it)) }
    }

    @Test
    fun typedTickersAreTriedAsTheyAre_barePonesAlsoAsNse() {
        assertEquals(listOf("AAPL", "AAPL.NS"), MarketSymbols.candidates(" aapl "))
        assertEquals(listOf("7203.T"), MarketSymbols.candidates("7203.t"))
        assertEquals(listOf("^GSPC"), MarketSymbols.candidates("^gspc"))
        assertEquals(listOf("BTC-USD"), MarketSymbols.candidates("btc-usd"))
        assertEquals(listOf("EURUSD=X"), MarketSymbols.candidates("eurusd=x"))
        assertEquals(listOf("GC=F"), MarketSymbols.candidates("gc=f"))
        assertTrue(MarketSymbols.candidates("  ").isEmpty())
        assertTrue(MarketSymbols.candidates("what is this?").isEmpty())
    }

    @Test
    fun exchangeAndNameTidying() {
        assertEquals("TSE", MarketNames.exchange("JPX", "Tokyo"))
        assertEquals("Tallinn", MarketNames.exchange("TAL", "Tallinn"))
        assertEquals("", MarketNames.exchange(null, null))
        assertEquals("Apple", MarketNames.short("Apple Inc."))
        assertEquals("Tata Consultancy Services", MarketNames.short("TATA CONSULTANCY SERVICES LTD"))
        assertEquals("Toyota Motor", MarketNames.short("Toyota Motor Corporation"))
        assertEquals("Co", MarketNames.short("Co"))
        assertTrue(MarketNames.short("x".repeat(100)).length <= 28)
    }

    @Test
    fun quotesKeepTheirCurrencyAndExchange() {
        val london = QuoteParser.parse("VOD.L", """{"chart":{"result":[{"meta":{"currency":"GBp","exchangeName":"LSE","regularMarketPrice":71.5,"chartPreviousClose":70.0}}]}}""")!!
        assertEquals("GBp", london.currency)
        assertEquals("LSE", london.exchange)
        val tokyo = QuoteParser.parse("7203.T", """{"chart":{"result":[{"meta":{"currency":"jpy","exchangeName":"JPX","regularMarketPrice":2850,"chartPreviousClose":2800}}]}}""")!!
        assertEquals("JPY", tokyo.currency)
        assertEquals("JPX", tokyo.exchange)
    }

    @Test
    fun currencySigns() {
        assertEquals("₹", CurrencySymbols.prefix("INR"))
        assertEquals("$", CurrencySymbols.prefix("USD"))
        assertEquals("£", CurrencySymbols.prefix("GBP"))
        assertEquals("€", CurrencySymbols.prefix("EUR"))
        assertEquals("¥", CurrencySymbols.prefix("JPY"))
        assertEquals("HK$", CurrencySymbols.prefix("HKD"))
        assertEquals("", CurrencySymbols.prefix("GBp"))
        assertEquals("p", CurrencySymbols.suffix("GBp"))
        assertEquals("XYZ ", CurrencySymbols.prefix("xyz"))
        assertEquals("", CurrencySymbols.prefix(""))
        assertEquals(0, CurrencySymbols.digits("JPY", BigDecimal("2850")))
        assertEquals(2, CurrencySymbols.digits("USD", BigDecimal("190.5")))
        assertEquals(4, CurrencySymbols.digits("USD", BigDecimal("0.00123")))
        assertEquals(2, CurrencySymbols.digits("GBp", BigDecimal("71.5")))
        assertEquals(2, CurrencySymbols.digits("QQQ", BigDecimal("10")))
    }

    @Test
    fun rowsShowTheirOwnCurrencyAndExchange_andOldListsStillWork() {
        val settings = WatchSettings(
            true,
            listOf(WatchSymbol("RELIANCE.NS", "Reliance"), WatchSymbol("AAPL", "Apple", "NASDAQ"), WatchSymbol("^GSPC", "S&P 500"), WatchSymbol("EURUSD=X", "EUR/USD"), WatchSymbol("GC=F", "Gold futures")),
            showDollar = false, showGold = false
        )
        val quotes = mapOf(
            "RELIANCE.NS" to Quote("RELIANCE.NS", BigDecimal("2950"), BigDecimal("2900"), "INR", exchange = "NSI"),
            "AAPL" to Quote("AAPL", BigDecimal("190"), BigDecimal("188"), "USD", exchange = "NMS"),
            "^GSPC" to Quote("^GSPC", BigDecimal("5000"), BigDecimal("4990"), "USD"),
            "EURUSD=X" to Quote("EURUSD=X", BigDecimal("1.08"), BigDecimal("1.07"), "USD"),
            "GC=F" to Quote("GC=F", BigDecimal("2400"), BigDecimal("2380"), "USD")
        )
        val rows = MarketSymbols.rows(settings, quotes)
        assertEquals(listOf("INR", "USD", "", "", "USD"), rows.map { it.currency })
        assertEquals("RELIANCE.NS · NSE", rows[0].detail)
        assertEquals("AAPL · NASDAQ", rows[1].detail)
        assertEquals(MarketKind.INDEX, rows[2].kind)
        assertEquals(MarketKind.CURRENCY, rows[3].kind)
        assertEquals(MarketKind.COMMODITY, rows[4].kind)
    }
}
