package com.zyke.message.domain

import com.zyke.message.domain.budget.DayBreakdown
import com.zyke.message.domain.budget.DayBreakdowns
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DayBreakdownTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 20)
    private val c = MessageClassifier()
    private var id = 1L
    private fun sms(body: String, on: LocalDate) =
        c.classify(SmsMessage(id++, "AD-HDFCBK", body, on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true))

    @Test
    fun aDayIsSplitIntoSpendsAndCredits_andOtherDaysAreLeftOut() {
        val messages = listOf(
            sms("Rs.450.00 debited from A/c XX1234 on 20-10-26 to VPA cafe@okhdfc (UPI 1)", day),
            sms("Rs.150.00 debited from A/c XX1234 on 20-10-26 to VPA cafe@okhdfc (UPI 2)", day),
            sms("Credit Alert! Rs.1000.00 credited to HDFC Bank A/c XX1234 on 20-10-26 from VPA ravi@okhdfc (UPI 3)", day),
            sms("Rs.99.00 debited from A/c XX1234 on 19-10-26 to VPA other@okhdfc (UPI 4)", day.minusDays(1))
        )
        val b = DayBreakdowns.build(messages, day, zone)
        assertEquals(2, b.spent.size)
        assertEquals(1, b.received.size)
        val cats = DayBreakdown.categories(b.spent)
        assertEquals(0, cats.sumOf { it.total }.compareTo(java.math.BigDecimal("600")))
    }
}
