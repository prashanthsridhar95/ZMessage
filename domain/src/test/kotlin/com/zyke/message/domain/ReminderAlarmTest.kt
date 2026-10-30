package com.zyke.message.domain

import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.Reminders
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderAlarmTest {
    private val now = LocalDateTime.of(2026, 10, 30, 10, 0)

    private fun reminder(id: String, date: LocalDate, time: LocalTime?, monthly: Boolean = false) =
        Reminder(id, id, null, date, monthly, time)

    @Test
    fun theEarliestUpcomingReminderWithATimeIsNext_andOnesWithoutATimeAreIgnored() {
        val list = listOf(
            reminder("late", LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)),
            reminder("soon", LocalDate.of(2026, 10, 30), LocalTime.of(12, 0)),
            reminder("quiet", LocalDate.of(2026, 10, 30), null)
        )
        assertEquals("soon", Reminders.nextAlarm(list, now, emptySet())?.reminder?.id)
    }

    @Test
    fun aReminderAlreadyPostedIsSkipped_andAOneOffThatPassedIsGone() {
        val list = listOf(reminder("a", LocalDate.of(2026, 10, 30), LocalTime.of(12, 0)))
        val first = Reminders.nextAlarm(list, now, emptySet())!!
        assertNull(Reminders.nextAlarm(list, now, setOf(first.key)))
        assertNull(Reminders.nextAlarm(list, now.plusDays(1), emptySet()))
    }

    @Test
    fun aMonthlyReminderMovesToNextMonthOncePosted() {
        val list = listOf(reminder("rent", LocalDate.of(2026, 10, 30), LocalTime.of(9, 0), monthly = true))
        // At 10:00 today's 9:00 has passed, so the next one is in November.
        val next = Reminders.nextAlarm(list, now, emptySet())!!
        assertEquals(LocalDate.of(2026, 11, 30), next.on)
    }

    @Test
    fun aMomentJustPassedStillFires_soTwoAtTheSameMinuteBothDo() {
        val list = listOf(
            reminder("a", LocalDate.of(2026, 10, 30), LocalTime.of(10, 0)),
            reminder("b", LocalDate.of(2026, 10, 30), LocalTime.of(10, 0))
        )
        val first = Reminders.nextAlarm(list, now.plusSeconds(20), emptySet())!!
        val second = Reminders.nextAlarm(list, now.plusSeconds(20), setOf(first.key))!!
        assertEquals(setOf("a", "b"), setOf(first.reminder.id, second.reminder.id))
    }

    @Test
    fun theDigestMentionsTodaysAndTomorrowsEvents_butNotLaterOnes() {
        val events = listOf(
            com.zyke.message.domain.calendar.CalendarEvent("1", "Dentist", LocalDate.of(2026, 10, 30), LocalTime.of(17, 30)),
            com.zyke.message.domain.calendar.CalendarEvent("2", "Dinner", LocalDate.of(2026, 10, 31)),
            com.zyke.message.domain.calendar.CalendarEvent("3", "Trip", LocalDate.of(2026, 11, 5))
        )
        val digest = com.zyke.message.domain.insights.DailyDigest.build(
            emptyList(), com.zyke.message.domain.model.UserMessageState(),
            now.atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault(), emptyList(), events
        )!!
        assertEquals(listOf("Dentist", "Dinner"), digest.items.map { it.title })
        assertEquals(true, digest.items[0].detail.contains("5:30"))
    }
}
