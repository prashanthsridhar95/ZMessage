package com.zyke.message.domain.plan

import com.zyke.message.domain.forecast.OutflowItem
import com.zyke.message.domain.forecast.OutflowSource
import com.zyke.message.domain.forecast.UpcomingOutflow
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffordabilityTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 15)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val plan = MoneyPlan(salary = BigDecimal(50000), salaryDay = 1)

    private fun balanceMessage(balance: String) = ClassifiedMessage(
        message = SmsMessage(
            1, "AD-HDFCBK", "Rs.100 credited to A/c XX1234. Avl Bal Rs.$balance",
            today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), true
        ),
        category = MessageCategory.FOOD,
        merchant = Merchant("Friend", MerchantLogo.UPI, MessageCategory.FOOD),
        content = MessageContent.Transaction(BankName.HDFC, "Friend", "100", "1234", true, false, false)
    )

    private fun due(amount: Int) = UpcomingOutflow(
        today, today.plusDays(10), listOf(OutflowItem("Rent", BigDecimal(amount), today.plusDays(3), OutflowSource.REMINDER))
    )

    private fun check(
        price: Int,
        balance: String? = "60,000",
        outflow: UpcomingOutflow? = null,
        goals: List<Goal> = emptyList(),
        floor: BigDecimal? = null,
        plan: MoneyPlan = this.plan
    ) = Affordability.check(
        BigDecimal(price), listOfNotNull(balance?.let { balanceMessage(it) }), plan, goals, outflow, now, zone,
        lowBalanceFloor = floor
    )

    @Test
    fun comfortableWhenPlanBalanceAndBillsAllCover() {
        val v = check(3000, outflow = due(5000))
        assertEquals(AffordLevel.COMFORTABLE, v.level)
        assertTrue(v.reasons.size <= 3)
        assertEquals(BigDecimal(50000), v.spendableLeft)
        assertEquals(BigDecimal(5000), v.dueBeforeSalary)
        assertEquals(BigDecimal(52000), v.balanceAfter)
    }

    @Test
    fun tightBecauseOfBillsDueBeforeSalary() {
        val v = check(3000, balance = "12,000", outflow = due(5000))
        assertEquals(AffordLevel.TIGHT, v.level)
        assertTrue(v.reasons.first().text.contains("bills due before salary"))
    }

    @Test
    fun notNowWhenBalanceFallsBelowFloor() {
        val v = check(3000, balance = "6,000")
        assertEquals(AffordLevel.NOT_NOW, v.level)
        assertEquals(BigDecimal(5000), v.safetyFloor) // 10% of salary
        assertTrue(v.reasons.first().text.contains("safety floor"))
    }

    @Test
    fun ownFloorSettingIsUsed() {
        val v = check(3000, balance = "6,000", floor = BigDecimal(1000))
        assertEquals(AffordLevel.COMFORTABLE, v.level)
        assertEquals(BigDecimal(1000), v.safetyFloor)
    }

    @Test
    fun notNowWhenBillsWouldEmptyTheAccount() {
        val v = check(3000, balance = "6,000", outflow = due(5000), floor = BigDecimal(100))
        assertEquals(AffordLevel.NOT_NOW, v.level)
    }

    @Test
    fun cantTellWithoutAnyData() {
        val v = check(3000, balance = null, plan = MoneyPlan())
        assertEquals(AffordLevel.CANT_TELL, v.level)
        assertEquals(1, v.reasons.size)
        assertTrue(v.reasons.first().text.contains("nothing to compare"))
    }

    @Test
    fun overThePlanIsTightAndFarOverIsNotNow() {
        assertEquals(AffordLevel.TIGHT, check(60000, balance = "900,000").level)
        assertEquals(AffordLevel.NOT_NOW, check(90000, balance = "900,000").level)
    }

    @Test
    fun instalmentsOnlyFromFiveThousand() {
        assertTrue(check(4999).instalments.isEmpty())
        val v = check(12000)
        assertEquals(listOf(4000, 2000), v.instalments.map { it.perMonth.toInt() })
        assertEquals(listOf(3, 6), v.instalments.map { it.months })
    }

    @Test
    fun nearestGoalSlipsByTheShortfall() {
        val goal = Goal("g", "Goa trip", BigDecimal(100000), targetDate = LocalDate.of(2027, 4, 15))
        val v = check(60000, balance = "900,000", goals = listOf(goal))
        assertEquals("Goa trip", v.goalName)
        assertEquals(3, v.goalDelayWeeks)
        assertTrue(v.reasons.any { it.text.contains("Goa trip") })
    }

    @Test
    fun noGoalEffectWhenItFitsThePlan() {
        val goal = Goal("g", "Goa trip", BigDecimal(100000), targetDate = LocalDate.of(2027, 4, 15))
        val v = check(3000, goals = listOf(goal))
        assertNull(v.goalDelayWeeks)
        assertNotNull(v.spendableLeft)
    }
}
