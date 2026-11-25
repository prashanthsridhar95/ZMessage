package com.zyke.message.domain.chart

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    private fun d(v: Int) = BigDecimal(v)

    @Test
    fun aBarIsAShareOfTheTallestAndNeverNegative() {
        assertEquals(0.5f, ChartMath.barFraction(d(50), d(100)), 0.0001f)
        assertEquals(1f, ChartMath.barFraction(d(200), d(100)), 0f)
        assertEquals(0f, ChartMath.barFraction(d(0), d(100), minVisible = 0.04f), 0f)
        assertEquals(0f, ChartMath.barFraction(d(-5), d(100)), 0f)
        assertEquals(0f, ChartMath.barFraction(d(5), d(0)), 0f)
    }

    @Test
    fun aSmallPositiveBarIsStillVisible() {
        assertEquals(0.04f, ChartMath.barFraction(d(1), d(1000), minVisible = 0.04f), 0f)
        assertEquals(0.5f, ChartMath.barFraction(d(500), d(1000), minVisible = 0.04f), 0.0001f)
    }

    @Test
    fun percentagesAddUpToExactlyOneHundred() {
        val thirds = ChartMath.percentages(listOf(d(1), d(1), d(1)))
        assertEquals(100, thirds.sum())
        assertEquals(listOf(34, 33, 33), thirds)
        val mixed = ChartMath.percentages(listOf(d(8200), d(4000), d(3000), d(2500), d(1000), d(300)))
        assertEquals(100, mixed.sum())
        assertTrue(mixed[0] > mixed[1])
    }

    @Test
    fun percentagesOfNothingAreZeroAndNegativesCountAsNothing() {
        assertEquals(listOf(0, 0), ChartMath.percentages(listOf(d(0), d(0))))
        assertEquals(emptyList<Int>(), ChartMath.percentages(emptyList()))
        assertEquals(listOf(100, 0), ChartMath.percentages(listOf(d(10), d(-4))))
    }

    @Test
    fun aZeroEntryNeverGainsAPercent() {
        val p = ChartMath.percentages(listOf(d(1), d(0), d(1), d(1)))
        assertEquals(0, p[1])
        assertEquals(100, p.sum())
    }

    @Test
    fun barIndexIsClampedToTheEnds() {
        assertEquals(0, ChartMath.barIndexAt(-20f, 300f, 6))
        assertEquals(2, ChartMath.barIndexAt(120f, 300f, 6))
        assertEquals(5, ChartMath.barIndexAt(999f, 300f, 6))
        assertEquals(-1, ChartMath.barIndexAt(10f, 300f, 0))
        assertEquals(0, ChartMath.barIndexAt(10f, 0f, 3))
    }

    @Test
    fun pointIndexIsTheNearestPointEdgeToEdge() {
        assertEquals(0, ChartMath.pointIndexAt(0f, 100f, 5))
        assertEquals(2, ChartMath.pointIndexAt(52f, 100f, 5))
        assertEquals(4, ChartMath.pointIndexAt(500f, 100f, 5))
        assertEquals(0, ChartMath.pointIndexAt(40f, 100f, 1))
        assertEquals(-1, ChartMath.pointIndexAt(40f, 100f, 0))
    }

    @Test
    fun aTickNeedsANewBarAndEnoughTime() {
        assertTrue(ChartMath.shouldTick(-1, 3, 1000, 0))
        assertFalse(ChartMath.shouldTick(3, 3, 1000, 0))
        assertFalse(ChartMath.shouldTick(3, 4, 1030, 1000))
        assertTrue(ChartMath.shouldTick(3, 4, 1060, 1000))
        assertFalse(ChartMath.shouldTick(3, -1, 5000, 0))
    }

    @Test
    fun chartHeightFollowsTheWidthAndIsBounded() {
        assertEquals(150f, ChartMath.chartHeightDp(300f, 1f, 0.5f, 100f, 220f), 0.001f)
        assertEquals(100f, ChartMath.chartHeightDp(120f, 1f, 0.5f, 100f, 220f), 0f)
        assertEquals(220f, ChartMath.chartHeightDp(900f, 1f, 0.5f, 100f, 220f), 0f)
        assertEquals(100f, ChartMath.chartHeightDp(Float.NaN, 1f, 0.5f, 100f, 220f), 0f)
    }

    @Test
    fun largeTextMakesTheChartALittleTallerButNotUnbounded() {
        val normal = ChartMath.chartHeightDp(280f, 1f, 0.5f, 100f, 220f)
        val large = ChartMath.chartHeightDp(280f, 2f, 0.5f, 100f, 220f)
        assertTrue(large > normal)
        assertTrue(large <= 220f)
        assertEquals(large, ChartMath.chartHeightDp(280f, 3f, 0.5f, 100f, 220f), 0.001f)
    }

    @Test
    fun legendColumnsFitTheWidth() {
        assertEquals(2, ChartMath.legendColumns(280f, 120f, 8f))
        assertEquals(1, ChartMath.legendColumns(100f, 120f, 8f))
        assertEquals(4, ChartMath.legendColumns(2000f, 120f, 8f))
        assertEquals(1, ChartMath.legendColumns(0f, 120f, 8f))
    }

    @Test fun nearestIndexSnapsAndClamps() {
        assertEquals(-1, ChartMath.nearestIndex(5f, 0f, 100f, 0))
        assertEquals(0, ChartMath.nearestIndex(50f, 0f, 100f, 1))
        assertEquals(0, ChartMath.nearestIndex(-30f, 6f, 106f, 5))
        assertEquals(4, ChartMath.nearestIndex(500f, 6f, 106f, 5))
        assertEquals(2, ChartMath.nearestIndex(56f, 6f, 106f, 5))
        assertEquals(1, ChartMath.nearestIndex(30f, 6f, 106f, 5))
        assertEquals(0, ChartMath.nearestIndex(Float.NaN, 0f, 100f, 5))
        assertEquals(0, ChartMath.nearestIndex(10f, 50f, 50f, 5))
    }

    @Test fun windowIndexFollowsTheVisibleStretch() {
        // 10 points, window starts at point 4 and shows 4 of them across 0..100
        assertEquals(4, ChartMath.windowIndexAt(0f, 0f, 100f, 4f, 4f, 10))
        assertEquals(6, ChartMath.windowIndexAt(50f, 0f, 100f, 4f, 4f, 10))
        assertEquals(8, ChartMath.windowIndexAt(100f, 0f, 100f, 4f, 4f, 10))
        assertEquals(9, ChartMath.windowIndexAt(300f, 0f, 100f, 4f, 4f, 10))
        assertEquals(-1, ChartMath.windowIndexAt(5f, 0f, 100f, 0f, 1f, 0))
    }

    @Test fun stepsStayInsideAndStartAtTheLatest() {
        assertEquals(-1, ChartMath.stepIndex(-1, 1, 0))
        assertEquals(6, ChartMath.stepIndex(-1, -1, 7))
        assertEquals(6, ChartMath.stepIndex(6, 1, 7))
        assertEquals(0, ChartMath.stepIndex(0, -1, 7))
        assertEquals(3, ChartMath.stepIndex(2, 1, 7))
    }

    @Test fun tooltipNeverLeavesTheChart() {
        assertEquals(0f, ChartMath.tooltipLeft(2f, 80f, 300f), 0.001f)
        assertEquals(220f, ChartMath.tooltipLeft(299f, 80f, 300f), 0.001f)
        assertEquals(110f, ChartMath.tooltipLeft(150f, 80f, 300f), 0.001f)
        assertEquals(8f, ChartMath.tooltipLeft(2f, 80f, 300f, margin = 8f), 0.001f)
        assertEquals(212f, ChartMath.tooltipLeft(299f, 80f, 300f, margin = 8f), 0.001f)
        // wider than the container: start at the margin
        assertEquals(0f, ChartMath.tooltipLeft(100f, 400f, 300f), 0.001f)
    }

    @Test fun tooltipSitsAboveTheDotOrBelowWhenThereIsNoRoom() {
        assertEquals(60f, ChartMath.tooltipTop(100f, 32f, 150f, 8f), 0.001f)
        assertEquals(28f, ChartMath.tooltipTop(20f, 32f, 150f, 8f), 0.001f)
        assertEquals(109f, ChartMath.tooltipTop(149f, 32f, 150f, 8f), 0.001f)
        assertEquals(0f, ChartMath.tooltipTop(10f, 200f, 150f, 8f), 0.001f)
    }

    @Test fun changeAgainstAReference() {
        assertEquals(BigDecimal("25.0"), ChartMath.changePercent(BigDecimal(125), BigDecimal(100)))
        assertEquals(BigDecimal("-33.3"), ChartMath.changePercent(BigDecimal(200), BigDecimal(300)))
        assertEquals(BigDecimal("-50.0"), ChartMath.changePercent(BigDecimal(50), BigDecimal(100)))
        assertNull(ChartMath.changePercent(BigDecimal(5), BigDecimal.ZERO))
        assertNull(ChartMath.changePercent(BigDecimal(5), null))
        assertEquals(BigDecimal(25), ChartMath.change(BigDecimal(125), BigDecimal(100)))
        assertEquals(BigDecimal.ZERO, ChartMath.change(BigDecimal(125), null))
        assertEquals(1.68, ChartMath.changePercent(2487.35, 2446.15)!!, 0.0001)
        assertNull(ChartMath.changePercent(1.0, 0.0))
        assertNull(ChartMath.changePercent(1.0, Double.NaN))
    }

    @Test fun arrowsAndSignedPercentLabels() {
        assertEquals("\u25B2", ChartMath.changeArrow(0.5))
        assertEquals("\u25BC", ChartMath.changeArrow(-0.5))
        assertEquals("", ChartMath.changeArrow(0.0))
        assertEquals("+4.2%", ChartMath.signedPercent(4.24, 1))
        assertEquals("-1.0%", ChartMath.signedPercent(-1.0, 1))
        assertEquals("0.0%", ChartMath.signedPercent(-0.01, 1))
        assertEquals("+1.68%", ChartMath.signedPercent(1.68, 2))
    }
}
