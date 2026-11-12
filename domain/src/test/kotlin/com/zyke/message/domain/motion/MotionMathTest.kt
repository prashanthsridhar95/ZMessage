package com.zyke.message.domain.motion

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionMathTest {
    @Test
    fun theFirstItemDoesNotWaitAndEachNextOneWaitsALittleLonger() {
        assertEquals(0, MotionMath.staggerDelayMs(0))
        assertEquals(40, MotionMath.staggerDelayMs(1))
        assertEquals(120, MotionMath.staggerDelayMs(3))
    }

    @Test
    fun noItemWaitsLongerThanTheCapAllows() {
        assertEquals(MotionMath.STAGGER_MAX_DELAY_MS, MotionMath.staggerDelayMs(7))
        assertEquals(MotionMath.STAGGER_MAX_DELAY_MS, MotionMath.staggerDelayMs(50))
        assertEquals(0, MotionMath.staggerDelayMs(-3))
        // Wait plus a 240 ms entrance stays inside half a second.
        assertTrue(MotionMath.STAGGER_MAX_DELAY_MS + 240 <= 520)
    }

    @Test
    fun onlyTheFirstEightItemsStagger() {
        assertTrue(MotionMath.staggers(0))
        assertTrue(MotionMath.staggers(7))
        assertFalse(MotionMath.staggers(8))
        assertFalse(MotionMath.staggers(-1))
    }

    @Test
    fun progressStaysBetweenZeroAndOne() {
        assertEquals(0f, MotionMath.clampProgress(-0.4f), 0f)
        assertEquals(1f, MotionMath.clampProgress(3f), 0f)
        assertEquals(0.5f, MotionMath.clampProgress(0.5f), 0f)
        assertEquals(0f, MotionMath.clampProgress(Float.NaN), 0f)
        assertEquals(0f, MotionMath.clampProgress(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun aShareOfNothingIsFull() {
        assertEquals(1f, MotionMath.share(BigDecimal("10"), BigDecimal.ZERO), 0f)
        assertEquals(0.25f, MotionMath.share(BigDecimal("250"), BigDecimal("1000")), 0.0001f)
        assertEquals(1f, MotionMath.share(BigDecimal("2500"), BigDecimal("1000")), 0f)
        assertEquals(0f, MotionMath.share(BigDecimal("-5"), BigDecimal("1000")), 0f)
    }

    @Test
    fun aCountStartsAtTheOldValueAndEndsExactlyOnTheNewOne() {
        val from = BigDecimal("1000")
        val to = BigDecimal("2000.50")
        assertEquals(BigDecimal("1000.00"), MotionMath.countUp(from, to, 0f, 2))
        assertEquals(BigDecimal("1500.25"), MotionMath.countUp(from, to, 0.5f, 2))
        assertEquals(to, MotionMath.countUp(from, to, 1f, 2))
        assertEquals(to, MotionMath.countUp(from, to, 4f, 2))
    }

    @Test
    fun aCountCanGoDown() {
        assertEquals(BigDecimal("500"), MotionMath.countUp(BigDecimal("1000"), BigDecimal("0"), 0.5f, 0))
    }

    @Test
    fun aCountKeepsPaiseOnlyWhenTheTargetHasThem() {
        assertEquals(0, MotionMath.countScale(BigDecimal("1200")))
        assertEquals(0, MotionMath.countScale(BigDecimal("1200.00")))
        assertEquals(2, MotionMath.countScale(BigDecimal("1200.50")))
    }

    @Test
    fun aCountIsRedrawnAFixedNumberOfTimes() {
        assertEquals(0, MotionMath.quantise(0f, 12))
        assertEquals(6, MotionMath.quantise(0.5f, 12))
        assertEquals(12, MotionMath.quantise(1f, 12))
        assertEquals(12, MotionMath.quantise(9f, 12))
    }
}
