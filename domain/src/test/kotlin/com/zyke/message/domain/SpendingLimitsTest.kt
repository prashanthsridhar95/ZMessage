package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.LimitPeriod
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendingLimitsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun spend(amount: String, on: LocalDate) = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo SWIGGY\nOn 20/10/26\nRef $nextId",
            on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true
        )
    )

    private fun bd(value: String) = BigDecimal(value)

    @Test
    fun dailyAndMonthly_showAvailableAndDeficit() {
        val messages = listOf(
            spend("300.00", today), spend("450.00", today), // 750 today
            spend("1000.00", today.minusDays(3)),           // 1750 this month
            spend("999.00", today.minusMonths(1))           // last month: ignored
        )
        val result = SpendingLimits.build(
            messages, monthly = mapOf(MessageCategory.FOOD to bd("5000")), daily = mapOf(MessageCategory.FOOD to bd("500")), now, zone
        ).single()

        val daily = result.daily!!
        assertEquals(LimitPeriod.DAILY, daily.period)
        assertTrue(daily.isOver)
        assertEquals(0, bd("250").compareTo(daily.deficit))
        assertEquals(0, bd("0").compareTo(daily.available))

        val monthly = result.monthly!!
        assertFalse(monthly.isOver)
        assertEquals(0, bd("3250").compareTo(monthly.available))
        // 12 days left including today (20th to 31st): 3250 / 12 = 270 a day.
        assertEquals(12, result.daysLeft)
        assertEquals(0, bd("270").compareTo(result.perDayLeft!!))
    }

    @Test
    fun overMonthlyLimit_hasNoPerDayAllowance_andSortsFirst() {
        val messages = listOf(spend("900.00", today), spend("100.00", today))
        val result = SpendingLimits.build(
            messages,
            monthly = mapOf(MessageCategory.FOOD to bd("800"), MessageCategory.FUEL to bd("5000")),
            daily = emptyMap(), now, zone
        )
        assertEquals(MessageCategory.FOOD, result.first().category)
        assertTrue(result.first().isOver)
        assertNull(result.first().perDayLeft)
        assertNotNull(result.last().perDayLeft)
    }

    @Test
    fun categoriesWithoutALimit_areLeftOut() {
        assertTrue(SpendingLimits.build(listOf(spend("100.00", today)), emptyMap(), emptyMap(), now, zone).isEmpty())
        // A zero limit means none.
        assertTrue(SpendingLimits.build(emptyList(), mapOf(MessageCategory.FOOD to BigDecimal.ZERO), emptyMap(), Instant.EPOCH, zone).isEmpty())
    }
}
