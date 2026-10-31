package com.zyke.message.domain.goals

import com.zyke.message.domain.repository.GoalRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ContributionSource(val label: String) {
    MANUAL("Added by you"),
    TRANSFER("From a transfer")
}

/** One amount put towards a goal. A [ContributionSource.TRANSFER] one points at the payment message it came from. */
data class GoalContribution(
    val id: String,
    val goalId: String,
    val amount: BigDecimal,
    val date: LocalDate,
    val source: ContributionSource = ContributionSource.MANUAL,
    val messageId: Long? = null
)

interface GoalContributionRepository {
    val contributions: StateFlow<List<GoalContribution>>

    /** Adds the contribution, or replaces the one with the same id. */
    fun save(contribution: GoalContribution)

    fun remove(id: String)
}

object NoGoalContributions : GoalContributionRepository {
    override val contributions: StateFlow<List<GoalContribution>> = MutableStateFlow(emptyList())
    override fun save(contribution: GoalContribution) = Unit
    override fun remove(id: String) = Unit
}

/** Pure rules for contributions; [GoalSavings] applies them to the repositories. */
object GoalContributions {
    fun total(contributions: List<GoalContribution>, goalId: String): BigDecimal =
        contributions.filter { it.goalId == goalId }.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }

    fun forGoal(contributions: List<GoalContribution>, goalId: String): List<GoalContribution> =
        contributions.filter { it.goalId == goalId }.sortedByDescending { it.date }

    fun transferId(messageId: Long): String = "tx-$messageId"

    /** [contribution] added, and any other contribution from the same payment message dropped, so linking twice never doubles. */
    fun withTransfer(contributions: List<GoalContribution>, contribution: GoalContribution): List<GoalContribution> {
        val messageId = contribution.messageId
        return contributions.filter { it.id != contribution.id && (messageId == null || it.messageId != messageId) } + contribution
    }

    fun goalOf(contributions: List<GoalContribution>, messageId: Long): String? =
        contributions.firstOrNull { it.messageId == messageId }?.goalId

    fun openingId(goalId: String): String = "open-$goalId"
}

/**
 * Keeps [Goal.saved] equal to the sum of its contributions. A goal that has a saved amount but no history gets that
 * amount as an opening contribution, so goals made before the history existed keep what they had.
 */
class GoalSavings(
    private val goals: GoalRepository,
    private val contributions: GoalContributionRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    /** Migrates old goals and repairs any drift. Safe to call any number of times. */
    fun reconcile(today: LocalDate) {
        goals.goals.value.forEach { goal ->
            openIfNeeded(goal, today)
            syncSaved(goal.id)
        }
    }

    private fun openIfNeeded(goal: Goal, today: LocalDate) {
        if (goal.saved.signum() > 0 && contributions.contributions.value.none { it.goalId == goal.id }) {
            contributions.save(GoalContribution(GoalContributions.openingId(goal.id), goal.id, goal.saved, today))
        }
    }

    fun add(goalId: String, amount: BigDecimal, date: LocalDate): String? {
        if (amount.signum() <= 0 || goals.goals.value.none { it.id == goalId }) return null
        goals.goals.value.firstOrNull { it.id == goalId }?.let { openIfNeeded(it, date) }
        val id = newId()
        contributions.save(GoalContribution(id, goalId, amount, date))
        syncSaved(goalId)
        return id
    }

    /** Counts a payment towards [goalId]; linking the same message again (to this or another goal) replaces the earlier link. */
    fun linkTransfer(goalId: String, messageId: Long, amount: BigDecimal, date: LocalDate): Boolean {
        if (amount.signum() <= 0 || goals.goals.value.none { it.id == goalId }) return false
        goals.goals.value.firstOrNull { it.id == goalId }?.let { openIfNeeded(it, date) }
        val previous = GoalContributions.goalOf(contributions.contributions.value, messageId)
        GoalContributions.withTransfer(
            contributions.contributions.value,
            GoalContribution(GoalContributions.transferId(messageId), goalId, amount, date, ContributionSource.TRANSFER, messageId)
        ).let { next ->
            contributions.contributions.value.filter { old -> next.none { it.id == old.id } }.forEach { contributions.remove(it.id) }
            next.last().let { contributions.save(it) }
        }
        previous?.let { syncSaved(it) }
        syncSaved(goalId)
        return true
    }

    fun unlinkTransfer(messageId: Long) {
        val goalId = GoalContributions.goalOf(contributions.contributions.value, messageId) ?: return
        contributions.remove(GoalContributions.transferId(messageId))
        syncSaved(goalId)
    }

    fun remove(id: String) {
        val goalId = contributions.contributions.value.firstOrNull { it.id == id }?.goalId ?: return
        contributions.remove(id)
        syncSaved(goalId)
    }

    /** Puts a removed contribution back (for Undo). */
    fun restore(contribution: GoalContribution) {
        contributions.save(contribution)
        syncSaved(contribution.goalId)
    }

    private fun syncSaved(goalId: String) {
        val goal = goals.goals.value.firstOrNull { it.id == goalId } ?: return
        val total = GoalContributions.total(contributions.contributions.value, goalId).max(BigDecimal.ZERO)
        if (goal.saved.compareTo(total) != 0) goals.save(goal.copy(saved = total))
    }
}
