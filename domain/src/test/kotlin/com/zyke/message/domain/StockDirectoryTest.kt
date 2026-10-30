package com.zyke.message.domain

import com.zyke.message.domain.markets.StockDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockDirectoryTest {
    @Test
    fun aFewLettersFindTheCompany_byNameOrTicker() {
        assertEquals("RELIANCE.NS", StockDirectory.search("reli").first().symbol)
        assertEquals("TCS.NS", StockDirectory.search("tcs").first().symbol)
        assertEquals("HDFCBANK.NS", StockDirectory.search("hdfc bank").first().symbol)
        assertTrue(StockDirectory.search("tata").map { it.symbol }.containsAll(listOf("TATAMOTORS.NS", "TATASTEEL.NS", "TCS.NS")))
    }

    @Test
    fun nothingTypedOffersThePopularOnes_andNonsenseOffersNothing() {
        assertEquals(6, StockDirectory.search("").size)
        assertEquals(emptyList<Any>(), StockDirectory.search("qqqzzz"))
    }

    @Test
    fun indicesAreFoundToo() {
        assertEquals("^NSEI", StockDirectory.search("nifty").first().symbol)
    }
}
