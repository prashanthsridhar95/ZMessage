package com.zyke.message.domain.plan

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanActionsTest {
    private val today = LocalDate.of(2026, 10, 31) // a Saturday
    private val items = listOf("a", "b", "c")
    private fun keyOf(s: String) = PlanActions.key(PlanKind.AGENDA, s)
    private fun visible(state: PlanActionState, on: LocalDate = today) = PlanActions.visible(items, state, on, ::keyOf)

    @Test
    fun nothingHiddenLeavesTheSameList() {
        assertSame(items, visible(PlanActionState()))
    }

    @Test
    fun dismissedItemStaysGoneOnEveryLaterDay() {
        val state = PlanActions.dismiss(PlanActionState(), keyOf("b"), "B", PlanKind.AGENDA)
        assertEquals(listOf("a", "c"), visible(state))
        assertEquals(listOf("a", "c"), visible(state, today.plusYears(1)))
    }

    @Test
    fun snoozedItemIsHiddenUntilItsDayThenReturns() {
        val until = today.plusDays(3)
        val state = PlanActions.snooze(PlanActionState(), keyOf("a"), "A", PlanKind.AGENDA, until, today)
        assertEquals(listOf("b", "c"), visible(state))
        assertEquals(listOf("b", "c"), visible(state, until.minusDays(1)))
        assertEquals(items, visible(state, until))
        assertEquals(items, visible(state, until.plusDays(5)))
    }

    @Test
    fun snoozeToTodayOrThePastIsTakenAsTomorrow() {
        val state = PlanActions.snooze(PlanActionState(), keyOf("a"), "A", PlanKind.AGENDA, today.minusDays(2), today)
        assertEquals(today.plusDays(1), state.hides.getValue(keyOf("a")).until)
        assertEquals(listOf("b", "c"), visible(state))
    }

    @Test
    fun snoozingADismissedItemTurnsItIntoASnooze() {
        var state = PlanActions.dismiss(PlanActionState(), keyOf("a"), "A", PlanKind.AGENDA)
        state = PlanActions.snooze(state, keyOf("a"), "A", PlanKind.AGENDA, today.plusDays(1), today)
        assertTrue(state.hides.getValue(keyOf("a")).isSnooze)
        assertEquals(items, visible(state, today.plusDays(1)))
    }

    @Test
    fun restoreBringsItBackAndUnknownKeyChangesNothing() {
        val state = PlanActions.dismiss(PlanActionState(), keyOf("a"), "A", PlanKind.AGENDA)
        assertEquals(items, visible(PlanActions.restore(state, keyOf("a"))))
        assertSame(state, PlanActions.restore(state, "nope"))
    }

    @Test
    fun cleanDropsExpiredSnoozesButKeepsDismissalsAndLiveSnoozes() {
        var state = PlanActionState()
        state = PlanActions.snooze(state, "agenda:old", "Old", PlanKind.AGENDA, today.plusDays(1), today)
        state = PlanActions.snooze(state, "agenda:live", "Live", PlanKind.AGENDA, today.plusDays(9), today)
        state = PlanActions.dismiss(state, "agenda:gone", "Gone", PlanKind.AGENDA)
        val later = PlanActions.clean(state, today.plusDays(2))
        assertEquals(setOf("agenda:live", "agenda:gone"), later.hides.keys)
        assertSame(later, PlanActions.clean(later, today.plusDays(2)))
    }

    @Test
    fun hideForReportsOnlyTheHoldingHide() {
        val state = PlanActions.snooze(PlanActionState(), "bill:a", "A", PlanKind.BILL, today.plusDays(2), today)
        assertEquals(today.plusDays(2), PlanActions.hideFor("bill:a", state, today)?.until)
        assertNull(PlanActions.hideFor("bill:a", state, today.plusDays(2)))
        assertNull(PlanActions.hideFor("bill:b", state, today))
    }

    @Test
    fun snoozeOptionsLandOnTheRightDays() {
        assertEquals(today.plusDays(1), SnoozeOption.TOMORROW.until(today))
        assertEquals(today.plusDays(3), SnoozeOption.THREE_DAYS.until(today))
        assertEquals(LocalDate.of(2026, 11, 2), SnoozeOption.NEXT_WEEK.until(today)) // Monday after Saturday
        assertEquals(LocalDate.of(2026, 11, 9), SnoozeOption.NEXT_WEEK.until(LocalDate.of(2026, 11, 2))) // a Monday goes a week
    }

    @Test
    fun anOverdueBillCanBeSnoozedAndStillReportsItsHide() {
        val key = PlanActions.key(PlanKind.BILL, "card-1")
        val state = PlanActions.snooze(PlanActionState(), key, "Card bill", PlanKind.BILL, today.plusDays(3), today)
        assertEquals("Card bill", PlanActions.hideFor(key, state, today)?.label)
    }
}
