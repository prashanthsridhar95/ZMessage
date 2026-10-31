package com.zyke.message.domain

import com.zyke.message.domain.budget.DailyBasis
import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.PlanInsights
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DailyBudgetTrendTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun spend(amount: String, on: LocalDate): ClassifiedMessage = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo Corner Shop\nOn ${on.format(DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef ${nextId * 11}",
            on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true
        )
    )

    /** [days] spending days in the month, each of [each]; a month total of days * each. */
    private fun month(year: Int, month: Int, days: Int, each: String): List<ClassifiedMessage> =
        (1..days).map { spend(each, LocalDate.of(year, month, it)) }

    private fun insights(spendable: String) = PlanInsights(
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal(spendable), BigDecimal.ZERO, BigDecimal.ZERO, 10, null,
        null, null, emptyList(), BigDecimal.ZERO, null, null, null
    )

    @Test
    fun normal_threeMonths_isTheTypicalDailyAverage() {
        // 30-day Sep, 31-day Aug and Jul (Jul = 31, Aug = 31, Sep = 30), 20 days of 300 = 6000 each.
        val messages = month(2026, 7, 20, "300") + month(2026, 8, 20, "300") + month(2026, 9, 20, "300")
        val result = DailyBudgets.fromTrend(messages, today, zone)!!
        assertEquals(DailyBasis.TREND, result.basis)
        assertEquals(3, result.cyclesUsed)
        // 6000/31 = 193.5, 6000/31, 6000/30 = 200: median 193.5 -> 190
        assertEquals(BigDecimal("190"), result.amount)
    }

    @Test
    fun outlierMonth_doesNotMoveIt() {
        val messages = month(2026, 7, 20, "300") + month(2026, 8, 20, "300") + month(2026, 9, 20, "9000")
        val result = DailyBudgets.fromTrend(messages, today, zone)!!
        assertEquals(BigDecimal("190"), result.amount)
    }

    @Test
    fun thinMonths_areIgnored_andNothingElseMeansNull() {
        // Only 3 spending days in each earlier month, nothing this month yet.
        val thin = month(2026, 8, 3, "5000") + month(2026, 9, 3, "5000")
        assertNull(DailyBudgets.fromTrend(thin, today, zone))
        assertNull(DailyBudgets.fromTrend(emptyList(), today, zone))
    }

    @Test
    fun noFinishedMonth_usesThisMonthSoFar_onlyAfterAWeek() {
        val messages = month(2026, 10, 14, "100")
        val result = DailyBudgets.fromTrend(messages, today, zone)!!
        assertEquals(0, result.cyclesUsed)
        // 1400 over the 19 days before today = 73.6 -> 70
        assertEquals(BigDecimal("70"), result.amount)
        assertNull(DailyBudgets.fromTrend(messages, LocalDate.of(2026, 10, 7), zone))
    }

    @Test
    fun excludedSpending_isNotCounted() {
        val base = month(2026, 9, 20, "300")
        val excluded = (1..20).map { spend("5000", LocalDate.of(2026, 9, it)) }
        val invest = excluded.take(7).map { it.copy(category = MessageCategory.INVESTMENTS) }
        val aside = excluded.drop(7).map { it.copy(setAside = SetAside.EXCLUDED) }
        val result = DailyBudgets.fromTrend(base + invest + aside, today, zone)!!
        // 6000 / 30 = 200
        assertEquals(BigDecimal("200"), result.amount)
    }

    @Test
    fun rounding_downToTens_andFiftiesAboveAThousand() {
        // 20 days * 1000 = 20000 / 30 = 666 -> 660
        assertEquals(BigDecimal("660"), DailyBudgets.fromTrend(month(2026, 9, 20, "1000"), today, zone)!!.amount)
        // 30 days * 1234 = 37020 / 30 = 1234 -> 1200
        assertEquals(BigDecimal("1200"), DailyBudgets.fromTrend(month(2026, 9, 30, "1234"), today, zone)!!.amount)
    }

    @Test
    fun priority_customThenPlanThenTrend() {
        val messages = month(2026, 9, 20, "300")
        val plan = insights("30000")
        val custom = DailyBudgets.resolve(BigDecimal("500"), plan, messages, today, zone)!!
        assertEquals(DailyBasis.CUSTOM, custom.basis)
        assertEquals(BigDecimal("500"), custom.amount)
        val fromPlan = DailyBudgets.resolve(null, plan, messages, today, zone)!!
        assertEquals(DailyBasis.PLAN, fromPlan.basis)
        assertEquals(BigDecimal("967"), fromPlan.amount.setScale(0)) // 30000 / 31
        val trend = DailyBudgets.resolve(null, insights("0"), messages, today, zone)!!
        assertEquals(DailyBasis.TREND, trend.basis)
        assertNotNull(trend.cyclesUsed)
        assertNull(DailyBudgets.resolve(null, null, emptyList(), today, zone))
    }
}
