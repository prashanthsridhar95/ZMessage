package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.forecast.SpendForecasts
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendForecastTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 10)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val c = MessageClassifier()
    private var id = 1L

    private fun spend(amount: String, to: String, on: LocalDate) = c.classify(
        SmsMessage(id++, "AD-HDFCBK", "Rs.$amount debited from A/c XX1234 on ${on.dayOfMonth}-${on.monthValue}-26 to VPA $to@okhdfc (UPI ${id * 13})", on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true)
    )

    @Test
    fun noSpendingAtAll_saysNothing() {
        assertNull(SpendForecasts.build(emptyList(), BigDecimal(5000), now, zone, CycleRule.CALENDAR))
    }

    @Test
    fun atThisPace_theBalanceRunsDownAndTheShortfallGoesOnACard() {
        // 10 days in, 5,000 spent: 500 a day. 21 days to go: 10,500 more. A balance of 8,000 falls short by 2,500.
        val messages = (1..10).map { spend("500.00", "swiggy", LocalDate.of(2026, 10, it)) }
        val f = SpendForecasts.build(messages, BigDecimal(8000), now, zone, CycleRule.CALENDAR)!!
        assertEquals(0, f.bankPerDay.compareTo(BigDecimal(500)))
        assertEquals(0, f.projectedBankSpend.compareTo(BigDecimal(10_500)))
        assertEquals(0, f.endBalance!!.compareTo(BigDecimal(-2_500)))
        assertEquals(0, f.cardNeeded!!.compareTo(BigDecimal(2_500)))
        assertNotNull(f.runsOutOn)
    }

    @Test
    fun aBalanceThatLasts_needsNoCard() {
        val messages = (1..10).map { spend("100.00", "swiggy", LocalDate.of(2026, 10, it)) }
        val f = SpendForecasts.build(messages, BigDecimal(50_000), now, zone, CycleRule.CALENDAR)!!
        assertNull(f.cardNeeded)
        assertNull(f.runsOutOn)
        assertTrue(f.endBalance!!.signum() > 0)
    }

    @Test
    fun aCategoryAboveItsUsual_isSuggestedForCuttingBack() {
        val previous = (1..9).map { spend("100.00", "swiggy", LocalDate.of(2026, 9, it)) } // 900 last cycle
        val now = (1..10).map { spend("400.00", "swiggy", LocalDate.of(2026, 10, it)) } // 4,000 so far, far above usual
        val f = SpendForecasts.build(previous + now, BigDecimal(50_000), this.now, zone, CycleRule.CALENDAR)!!
        val cut = f.suggestions.first()
        assertEquals(com.zyke.message.domain.forecast.CutReason.ABOVE_USUAL, cut.reason)
        assertTrue(cut.saving.signum() > 0)
    }
}
