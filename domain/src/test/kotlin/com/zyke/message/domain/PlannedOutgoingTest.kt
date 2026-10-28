package com.zyke.message.domain

import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedOutgoingTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 28)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val c = MessageClassifier()
    private fun at(h: Int) = today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun aRegularOutgoingTheUserListed_isNotSpentFromTheDaysAllowance() {
        val netflix = c.classify(SmsMessage(1, "AD-HDFCBK", "Rs.650.00 debited from A/c XX1234 on 28-10-26 to VPA netflix@okhdfc (UPI 111)", at(9), true))
        val lunch = c.classify(SmsMessage(2, "AD-HDFCBK", "Rs.350.00 debited from A/c XX1234 on 28-10-26 to VPA cafe@okhdfc (UPI 222)", at(13), true))
        val plan = listOf(PlanItem("p", PlanItemKind.OUTFLOW, "Netflix", BigDecimal(650), null))

        val withoutPlan = DailyBudgets.status(listOf(netflix, lunch), BigDecimal(1500), now, zone)
        val withPlan = DailyBudgets.status(listOf(netflix, lunch), BigDecimal(1500), now, zone, plan)

        assertEquals(0, withoutPlan.spentToday.compareTo(BigDecimal(1000)))
        assertEquals(0, withPlan.spentToday.compareTo(BigDecimal(350)))
        assertEquals(0, withPlan.leftToday.compareTo(BigDecimal(1150)))
    }
}
