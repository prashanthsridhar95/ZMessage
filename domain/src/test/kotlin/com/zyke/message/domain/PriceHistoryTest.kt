package com.zyke.message.domain

import com.zyke.message.domain.markets.ChartRange
import com.zyke.message.domain.markets.PriceHistories
import com.zyke.message.domain.markets.PriceHistory
import com.zyke.message.domain.markets.PriceHistoryParser
import com.zyke.message.domain.markets.PricePoint
import com.zyke.message.domain.markets.VolumeText
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceHistoryTest {
    // 2026-11-02 (Monday) 09:15 IST = 03:45 UTC = 1793591100
    private val sample = """
        {"chart":{"result":[{"meta":{"currency":"INR","symbol":"RELIANCE.NS","exchangeName":"NSI","fullExchangeName":"NSE",
          "longName":"Reliance Industries Limited","shortName":"RELIANCE INDUSTRIES","regularMarketPrice":1250.5,
          "chartPreviousClose":1240.0,"previousClose":1240.0,"regularMarketDayHigh":1255.0,"regularMarketDayLow":1238.25,
          "regularMarketVolume":5400000,"fiftyTwoWeekHigh":1608.95,"fiftyTwoWeekLow":1114.85,"gmtoffset":19800},
          "timestamp":[1793591100,1793591400,1793591700,1793592000,1793592300],
          "indicators":{"quote":[{"open":[1241.0,1244.0,null,1246.0,1249.0],"close":[1244.0,null,1246.5,1249.0,1250.5],
          "high":[1,2,3,4,5],"low":[1,2,3,4,5],"volume":[1,2,3,4,5]}]}}],"error":null}}
    """.trimIndent()

    @Test fun readsMetaAndDropsNullCloses() {
        val h = PriceHistoryParser.parse("RELIANCE.NS", ChartRange.DAY, sample)!!
        assertEquals("Reliance Industries Limited", h.name)
        assertEquals("INR", h.currency)
        assertEquals("NSE", h.exchange)
        assertEquals(1250.5, h.price!!, 0.0)
        assertEquals(1240.0, h.previousClose!!, 0.0)
        assertEquals(1241.0, h.open!!, 0.0)
        assertEquals(1255.0, h.dayHigh!!, 0.0)
        assertEquals(1238.25, h.dayLow!!, 0.0)
        assertEquals(5_400_000L, h.volume)
        assertEquals(1608.95, h.high52!!, 0.0)
        assertEquals(1114.85, h.low52!!, 0.0)
        assertEquals(19800, h.offsetSeconds)
        assertEquals(4, h.points.size)
        assertTrue(h.hasChart)
        assertEquals(1244.0, h.points.first().value, 0.0)
    }

    @Test fun dayChangeIsFromThePreviousClose() {
        val h = PriceHistoryParser.parse("X", ChartRange.DAY, sample)!!
        assertEquals(10.5, h.change!!, 1e-9)
        assertEquals(10.5 / 1240.0 * 100, h.changePercent!!, 1e-9)
    }

    @Test fun longerChangeIsFromTheFirstPointAndOpenIsOnlyForADay() {
        val h = PriceHistoryParser.parse("X", ChartRange.MONTH, sample)!!
        assertEquals(1250.5 - 1244.0, h.change!!, 1e-9)
        assertNull(h.open)
    }

    @Test fun otherPeriodKeepsOpenAndPreviousCloseFromTheDayChart() {
        val day = PriceHistoryParser.parse("X", ChartRange.DAY, sample)!!
        val noMeta = """{"chart":{"result":[{"meta":{"regularMarketPrice":1250.5,"chartPreviousClose":1100.0},"timestamp":[1,2],"indicators":{"quote":[{"close":[1100.0,1250.5]}]}}]}}"""
        val week = PriceHistoryParser.parse("X", ChartRange.WEEK, noMeta)!!
        assertNull(week.previousClose)
        val merged = week.withStatsFrom(day)
        assertEquals(1240.0, merged.previousClose!!, 0.0)
        assertEquals(1241.0, merged.open!!, 0.0)
        assertEquals("Reliance Industries Limited", merged.name)
        assertEquals(2, merged.points.size)
        // another ticker's figures are never borrowed
        assertNull(week.copy(symbol = "Y").withStatsFrom(day).open)
    }

    @Test fun garbageAndErrorsGiveNull() {
        assertNull(PriceHistoryParser.parse("X", ChartRange.DAY, ""))
        assertNull(PriceHistoryParser.parse("X", ChartRange.DAY, "<html>"))
        assertNull(PriceHistoryParser.parse("X", ChartRange.DAY, """{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}"""))
        assertNull(PriceHistoryParser.parse("X", ChartRange.DAY, """{"chart":{"result":[{"meta":{},"timestamp":[],"indicators":{"quote":[{}]}}]}}"""))
    }

    @Test fun aPriceWithoutAChartStillGivesTheFigures() {
        val h = PriceHistoryParser.parse("X", ChartRange.DAY, """{"chart":{"result":[{"meta":{"regularMarketPrice":10.0,"currency":"usd"}}]}}""")!!
        assertFalse(h.hasChart)
        assertEquals("USD", h.currency)
        assertEquals(10.0, h.last!!, 0.0)
    }

    @Test fun poundsInPenceKeepTheirCase() {
        val h = PriceHistoryParser.parse("VOD.L", ChartRange.DAY, """{"chart":{"result":[{"meta":{"regularMarketPrice":70.5,"currency":"GBp"}}]}}""")!!
        assertEquals("GBp", h.currency)
    }

    @Test fun outOfOrderAndNonPositivePointsAreSkipped() {
        val json = """{"chart":{"result":[{"meta":{},"timestamp":[10,5,20,30],"indicators":{"quote":[{"close":[1.0,2.0,0,3.0]}]}}]}}"""
        val h = PriceHistoryParser.parse("X", ChartRange.MONTH, json)!!
        assertEquals(listOf(10L, 30L), h.points.map { it.time })
    }

    @Test fun downsampleKeepsEndsAndExtremesInOrder() {
        val pts = (0 until 1000).map { PricePoint(it.toLong(), if (it == 400) 500.0 else if (it == 700) -50.0 else (it % 7).toDouble()) }
        val out = PriceHistories.downsample(pts, 200)
        assertTrue(out.size <= 200)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        assertTrue(out.any { it.value == 500.0 })
        assertTrue(out.any { it.value == -50.0 })
        assertEquals(out.map { it.time }, out.map { it.time }.sorted())
        assertEquals(out.size, out.map { it.time }.distinct().size)
    }

    @Test fun smallListsAreNotTouched() {
        val pts = (0 until 50).map { PricePoint(it.toLong(), it.toDouble() + 1) }
        assertEquals(pts, PriceHistories.downsample(pts))
    }

    @Test fun niceTicksAreRoundAndInside() {
        val ticks = PriceHistories.niceTicks(1238.25, 1255.0, 4)
        assertTrue(ticks.size in 2..6)
        assertTrue(ticks.all { it >= 1238.25 && it <= 1255.0 })
        assertEquals(0, PriceHistories.tickDigits(listOf(1240.0, 1245.0, 1250.0)))
        assertEquals(1, PriceHistories.tickDigits(listOf(1.0, 1.5, 2.0)))
        assertEquals(2, PriceHistories.tickDigits(listOf(0.01, 0.02)))
        assertEquals(listOf(5.0), PriceHistories.niceTicks(5.0, 5.0))
    }

    @Test fun dayLabelsAreHoursInTheExchangeTime() {
        val h = PriceHistoryParser.parse("X", ChartRange.DAY, sample)!!
        val labels = PriceHistories.xLabels(h, 4, Locale.US)
        assertEquals("9 AM", labels.first().text)
        assertEquals(0f, labels.first().fraction, 0f)
        assertTrue(labels.all { it.fraction in 0f..1f })
    }

    @Test fun weekLabelsAreTheFirstPointOfEachDay() {
        val day = 86_400L
        val start = 1793591100L // Monday 09:15 IST
        val pts = (0 until 5).flatMap { d -> (0 until 4).map { PricePoint(start + d * day + it * 1800L, 100.0 + d + it) } }
        val h = PriceHistory("X", ChartRange.WEEK, "", "INR", "NSE", pts, null, null, null, null, null, null, null, null, 19800)
        val labels = PriceHistories.xLabels(h, 4, Locale.US)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri"), labels.map { it.text })
    }

    @Test fun momentLabelNamesTheDate() {
        val h = PriceHistoryParser.parse("X", ChartRange.MONTH, sample)!!
        assertEquals("Mon 2 Nov 2026", PriceHistories.momentLabel(h, 1793591100L, Locale.US))
    }

    @Test fun volumeIsShort() {
        assertEquals("950", VolumeText.short(950))
        assertEquals("12.5K", VolumeText.short(12_500))
        assertEquals("5.4M", VolumeText.short(5_400_000))
        assertEquals("120M", VolumeText.short(120_000_000))
        assertEquals("1.2B", VolumeText.short(1_200_000_000))
    }

    @Test fun everyRangeAsksYahooForSomethingValid() {
        ChartRange.entries.forEach { assertTrue(it.range.isNotEmpty() && it.interval.isNotEmpty() && it.period.isNotEmpty()) }
        assertEquals(listOf("1D", "1W", "1M", "3M", "6M", "YTD", "1Y", "5Y"), ChartRange.entries.map { it.label })
    }
}
