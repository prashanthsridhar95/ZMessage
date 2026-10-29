package com.zyke.message.domain

import com.zyke.message.domain.calendar.CalendarBuilder
import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.calendar.CalendarKind
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.reminders.Reminder
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 29)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val c = MessageClassifier()

    @Test
    fun eventsRemindersAndBills_appearOnTheirDays() {
        val bill = c.classify(SmsMessage(1, "AD-HDFCBK", "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:05-11-2026 pay https://hdfcbk.io/1234", today.minusDays(5).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true))
        val events = listOf(CalendarEvent("e", "Dentist", LocalDate.of(2026, 11, 3)))
        val rent = Reminder("r", "Rent", java.math.BigDecimal(20000), LocalDate.of(2026, 9, 1), repeatsMonthly = true)

        val november = CalendarBuilder.month(YearMonth.of(2026, 11), listOf(bill), listOf(rent), events, emptyList(), MoneyPlan(), now, zone)

        assertEquals(CalendarKind.EVENT, november.getValue(LocalDate.of(2026, 11, 3)).single().kind)
        assertEquals(CalendarKind.BILL, november.getValue(LocalDate.of(2026, 11, 5)).single().kind)
        assertEquals(CalendarKind.REMINDER, november.getValue(LocalDate.of(2026, 11, 1)).single().kind)
        // A month with nothing in it is empty, and the monthly reminder does not reach back before it began.
        val august = CalendarBuilder.month(YearMonth.of(2026, 8), listOf(bill), listOf(rent), events, emptyList(), MoneyPlan(), now, zone)
        assertTrue(august.isEmpty())
    }
}
