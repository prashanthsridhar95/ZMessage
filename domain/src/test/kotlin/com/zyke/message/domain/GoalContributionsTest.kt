package com.zyke.message.domain

import com.zyke.message.domain.goals.ContributionSource
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.goals.GoalContribution
import com.zyke.message.domain.goals.GoalContributionRepository
import com.zyke.message.domain.goals.GoalSavings
import com.zyke.message.domain.repository.GoalRepository
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalContributionsTest {
    private val today = LocalDate.of(2026, 10, 20)

    private class FakeGoals(initial: List<Goal>) : GoalRepository {
        override val goals = MutableStateFlow(initial)
        override fun save(goal: Goal) {
            goals.value = if (goals.value.any { it.id == goal.id }) goals.value.map { if (it.id == goal.id) goal else it } else goals.value + goal
        }
        override fun remove(id: String) { goals.value = goals.value.filter { it.id != id } }
    }

    private class FakeContribs(initial: List<GoalContribution> = emptyList()) : GoalContributionRepository {
        val flow = MutableStateFlow(initial)
        override val contributions: StateFlow<List<GoalContribution>> get() = flow
        override fun save(contribution: GoalContribution) {
            flow.value = flow.value.filter { it.id != contribution.id } + contribution
        }
        override fun remove(id: String) { flow.value = flow.value.filter { it.id != id } }
    }

    private fun savings(goals: FakeGoals, contribs: FakeContribs): GoalSavings {
        var n = 0
        return GoalSavings(goals, contribs) { "c${n++}" }
    }

    private fun goal(saved: String = "0") = Goal("g", "Trip", BigDecimal("1000"), BigDecimal(saved))

    @Test fun migration_turnsOldSavedAmountIntoOpeningContribution() {
        val goals = FakeGoals(listOf(goal("300")))
        val contribs = FakeContribs()
        val s = savings(goals, contribs)
        s.reconcile(today)
        s.reconcile(today)
        assertEquals(1, contribs.flow.value.size)
        assertEquals(0, BigDecimal("300").compareTo(contribs.flow.value.single().amount))
        assertEquals(0, BigDecimal("300").compareTo(goals.goals.value.single().saved))
    }

    @Test fun migration_leavesEmptyGoalsAlone() {
        val contribs = FakeContribs()
        savings(FakeGoals(listOf(goal())), contribs).reconcile(today)
        assertTrue(contribs.flow.value.isEmpty())
    }

    @Test fun add_keepsSavedInSync() {
        val goals = FakeGoals(listOf(goal("300")))
        val contribs = FakeContribs()
        val s = savings(goals, contribs)
        s.reconcile(today)
        val id = s.add("g", BigDecimal("200"), today)!!
        assertEquals(0, BigDecimal("500").compareTo(goals.goals.value.single().saved))
        s.remove(id)
        assertEquals(0, BigDecimal("300").compareTo(goals.goals.value.single().saved))
        assertNull(s.add("g", BigDecimal.ZERO, today))
        assertNull(s.add("missing", BigDecimal.TEN, today))
    }

    @Test fun linkingSameTransferTwiceNeverDoubles() {
        val goals = FakeGoals(listOf(goal(), Goal("h", "Fund", BigDecimal("500"))))
        val contribs = FakeContribs()
        val s = savings(goals, contribs)
        assertTrue(s.linkTransfer("g", 42, BigDecimal("250"), today))
        assertTrue(s.linkTransfer("g", 42, BigDecimal("250"), today))
        assertEquals(1, contribs.flow.value.size)
        assertEquals(ContributionSource.TRANSFER, contribs.flow.value.single().source)
        assertEquals(0, BigDecimal("250").compareTo(goals.goals.value.first { it.id == "g" }.saved))
        // Moving the link to another goal takes it off the first.
        s.linkTransfer("h", 42, BigDecimal("250"), today)
        assertEquals(1, contribs.flow.value.size)
        assertEquals(0, BigDecimal.ZERO.compareTo(goals.goals.value.first { it.id == "g" }.saved))
        assertEquals(0, BigDecimal("250").compareTo(goals.goals.value.first { it.id == "h" }.saved))
        s.unlinkTransfer(42)
        assertEquals(0, BigDecimal.ZERO.compareTo(goals.goals.value.first { it.id == "h" }.saved))
    }

    @Test fun firstAddOnAnOldGoalKeepsWhatItHad() {
        val goals = FakeGoals(listOf(goal("300")))
        savings(goals, FakeContribs()).add("g", BigDecimal("100"), today)
        assertEquals(0, BigDecimal("400").compareTo(goals.goals.value.single().saved))
    }
}
