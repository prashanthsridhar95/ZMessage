package com.zyke.message.domain

import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.goals.Goals
import com.zyke.message.domain.insights.DailyDigest
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.Reminders
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalsAndRemindersTest {
    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun goalProgress_saysHowMuchToPutAsideEachMonth() {
        val goal = Goal("g", "Goa trip", BigDecimal("60000"), BigDecimal("12000"), LocalDate.of(2027, 2, 15))
        val progress = Goals.progress(goal, today)
        assertEquals(0.2f, progress.fraction, 0.001f)
        assertEquals(0, BigDecimal("48000").compareTo(progress.remaining))
        // Oct to Feb is 4 months: 48,000 / 4.
        assertEquals(0, BigDecimal("12000").compareTo(progress.neededPerMonth!!))
        assertFalse(progress.isReached)
    }

    @Test
    fun reachedAndLateGoals() {
        assertTrue(Goals.progress(Goal("a", "Fund", BigDecimal("100"), BigDecimal("100")), today).isReached)
        val late = Goals.progress(Goal("b", "Gadget", BigDecimal("100"), BigDecimal("10"), today.minusDays(3)), today)
        assertTrue(late.isLate)
        assertNull(late.neededPerMonth)
        val ordered = Goals.all(
            listOf(Goal("a", "Done", BigDecimal("10"), BigDecimal("10")), Goal("b", "Soon", BigDecimal("10"), BigDecimal.ONE, today.plusDays(5))), today
        )
        assertEquals("Soon", ordered.first().goal.name)
    }

    @Test
    fun monthlyReminder_fallsOnItsDayEachMonth_andShortMonthsUseTheirLastDay() {
        val rent = Reminder("r", "Rent", BigDecimal("20000"), LocalDate.of(2026, 1, 31), repeatsMonthly = true)
        assertEquals(LocalDate.of(2026, 10, 31), Reminders.next(rent, today))
        assertEquals(LocalDate.of(2027, 2, 28), Reminders.next(rent, LocalDate.of(2027, 2, 10)))
        val once = Reminder("o", "Pay Ravi", null, today.minusDays(1))
        assertNull(Reminders.next(once, today))
        assertEquals(listOf("Rent"), Reminders.upcoming(listOf(once, rent), today).map { it.reminder.title })
    }

    @Test
    fun theMorningDigestMentionsTodaysReminders() {
        val now = today.atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant()
        val digest = DailyDigest.build(emptyList(), UserMessageState(), now, ZoneId.of("UTC"), listOf(Reminder("r", "Pay Ravi", BigDecimal("500"), today)))!!
        assertTrue(digest.lines.toString(), digest.short.contains("Pay Ravi"))
    }
}
