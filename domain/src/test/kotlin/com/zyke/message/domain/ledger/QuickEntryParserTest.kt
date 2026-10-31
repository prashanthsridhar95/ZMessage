package com.zyke.message.domain.ledger

import java.math.BigDecimal
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class QuickEntryParserTest {
    private fun p(s: String) = QuickEntryParser.parse(s)

    @Test fun plainAmountAndTitle() {
        val d = p("200 for auto")!!
        assertEquals(BigDecimal("200"), d.amount); assertEquals("Auto", d.title); assertEquals(ManualKind.EXPENSE, d.kind)
    }

    @Test fun rupeeSignAndCommas() {
        val d = p("₹1,250 dinner with Ravi")!!
        assertEquals(BigDecimal("1250"), d.amount); assertEquals("Dinner with Ravi", d.title)
    }

    @Test fun rsAndInrPrefixes() {
        assertEquals(BigDecimal("80.5"), p("paid Rs. 80.50 for tea")!!.amount)
        assertEquals("Tea", p("paid Rs. 80.50 for tea")!!.title)
        assertEquals(BigDecimal("300"), p("INR 300 parking")!!.amount)
        assertEquals(BigDecimal("45"), p("tea 45 rs")!!.amount)
        assertEquals("Tea", p("tea 45 rs")!!.title)
        assertEquals(BigDecimal("500"), p("500/- vegetables")!!.amount)
    }

    @Test fun kSuffix() {
        assertEquals(BigDecimal("2000"), p("2k rent advance")!!.amount)
        assertEquals(BigDecimal("1500"), p("₹1.5k gift")!!.amount)
        assertEquals(BigDecimal("2000"), p("2K")!!.amount)
    }

    @Test fun kindGuess() {
        assertEquals(ManualKind.INCOME, p("received 5000 from Ravi")!!.kind)
        assertEquals("Ravi", p("received 5000 from Ravi")!!.title)
        assertEquals(ManualKind.INCOME, p("got salary 45k")!!.kind)
        assertEquals("Salary", p("got salary 45k")!!.title)
        assertEquals(ManualKind.EXPENSE, p("spent 90 on snacks")!!.kind)
        assertEquals(ManualKind.EXPENSE, p("90 snacks")!!.kind)
    }

    @Test fun missingTitleFallsBack() {
        assertEquals("Cash spend", p("₹100")!!.title)
        assertEquals("Cash received", p("received 100")!!.title)
    }

    @Test fun markedMoneyBeatsBareNumber() {
        assertEquals(BigDecimal("300"), p("room 12 ₹300")!!.amount)
    }

    @Test fun usesFirstLineWithAnAmount() {
        val d = p("Shared from Notes\n250 lunch")!!
        assertEquals(BigDecimal("250"), d.amount); assertEquals("Lunch", d.title)
    }

    @Test fun junkAndNoAmountGiveNull() {
        assertNull(p(""))
        assertNull(p("   "))
        assertNull(p("just some words"))
        assertNull(p("0 for nothing"))
        assertNull(p("abc123def"))
        assertNull(p("₹"))
        assertNull(p("1234567890123456 card"))
    }

    @Test fun longTitleIsCut() {
        assertEquals(60, p("100 " + "x".repeat(200))!!.title.length)
    }
}
