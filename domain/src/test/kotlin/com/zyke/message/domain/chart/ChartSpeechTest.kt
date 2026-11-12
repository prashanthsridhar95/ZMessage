package com.zyke.message.domain.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSpeechTest {
    private val food = ChartSpeech.Slice("Food", "₹8,200", 31)
    private val travel = ChartSpeech.Slice("Travel", "₹4,000", 15)

    @Test
    fun sharesNameTheBiggestPartsWithAmountAndPercent() {
        assertEquals("Spending by category: Food ₹8,200, 31%; Travel ₹4,000, 15%.", ChartSpeech.shares("Spending by category", listOf(food, travel)))
    }

    @Test
    fun sharesSayHowManyMoreThereAre() {
        val many = List(8) { ChartSpeech.Slice("C$it", "₹${it + 1}", 10) }
        val text = ChartSpeech.shares("Spending by category", many, maxNamed = 3)
        assertEquals("Spending by category: C0 ₹1, 10%; C1 ₹2, 10%; C2 ₹3, 10%; and 5 more.", text)
    }

    @Test
    fun noSlicesSayNothingYet() {
        assertEquals("Spending by category: nothing yet.", ChartSpeech.shares("Spending by category", emptyList()))
    }

    @Test
    fun trendSaysCountTotalAndPeak() {
        assertEquals(
            "Spending by day: 30 days, total ₹12,000, highest ₹1,800 on 12 Oct.",
            ChartSpeech.trend("Spending by day", 30, "day", "₹12,000", "₹1,800", "12 Oct")
        )
        assertEquals("Spending by day: 1 day, total ₹0.", ChartSpeech.trend("Spending by day", 1, "day", "₹0", null, null))
        assertEquals("Spending by day: nothing yet.", ChartSpeech.trend("Spending by day", 0, "day", "₹0", null, null))
    }

    @Test
    fun pairReadsBothSides() {
        assertEquals("Income vs spending: income ₹5, spent ₹3.", ChartSpeech.pair("Income vs spending", "income", "₹5", "spent", "₹3"))
    }
}
