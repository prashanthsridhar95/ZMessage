package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.forecast.LimitSuggestions
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitSuggestionsTest {
    private val zone = ZoneId.of("UTC")
    private val now = LocalDate.of(2026, 10, 10).atTime(12, 0).atZone(zone).toInstant()
    private val c = MessageClassifier()
    private var id = 1L

    private fun food(amount: String, on: LocalDate) = c.classify(
        SmsMessage(id++, "AD-HDFCBK", "Rs.$amount debited from A/c XX1234 on ${on.dayOfMonth}-${on.monthValue}-26 to VPA swiggy@okhdfc (UPI ${id * 7})", on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true)
    )

    @Test
    fun aCategoryWithHistory_getsALimitAboutATenthUnderItsUsual() {
        // 4,000 in each of the last two months.
        val messages = listOf(
            food("2000.00", LocalDate.of(2026, 9, 5)), food("2000.00", LocalDate.of(2026, 9, 20)),
            food("2000.00", LocalDate.of(2026, 8, 5)), food("2000.00", LocalDate.of(2026, 8, 20))
        )
        val suggestions = LimitSuggestions.build(messages, emptyMap(), null, now, zone, CycleRule.CALENDAR)
        val food = suggestions.single()
        // Two months have spending (July has none), so the usual is 4,000 and the limit about a tenth under it.
        assertEquals(0, food.usual.compareTo(BigDecimal("4000")))
        assertEquals(0, food.suggested.compareTo(BigDecimal("3600")))
    }

    @Test
    fun anAlreadySensibleLimit_isNotSuggestedAgain() {
        val messages = listOf(food("3000.00", LocalDate.of(2026, 9, 5)), food("3000.00", LocalDate.of(2026, 8, 5)), food("3000.00", LocalDate.of(2026, 7, 5)))
        val suggested = LimitSuggestions.build(messages, emptyMap(), null, now, zone, CycleRule.CALENDAR).single().suggested
        val again = LimitSuggestions.build(messages, mapOf(messages.first().category to suggested), null, now, zone, CycleRule.CALENDAR)
        assertTrue(again.isEmpty())
    }

    @Test
    fun limitsAreScaledToWhatThePlanLeavesToSpend() {
        val messages = listOf(food("3000.00", LocalDate.of(2026, 9, 5)), food("3000.00", LocalDate.of(2026, 8, 5)), food("3000.00", LocalDate.of(2026, 7, 5)))
        val s = LimitSuggestions.build(messages, emptyMap(), BigDecimal(1000), now, zone, CycleRule.CALENDAR).single()
        assertTrue(s.suggested <= BigDecimal(1000))
    }
}
