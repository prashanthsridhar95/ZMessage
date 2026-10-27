package com.zyke.message.domain.repository

import com.zyke.message.domain.goals.Goal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface GoalRepository {
    val goals: StateFlow<List<Goal>>

    /** Adds the goal, or replaces the one with the same id. */
    fun save(goal: Goal)

    fun remove(id: String)
}

object NoGoals : GoalRepository {
    override val goals: StateFlow<List<Goal>> = MutableStateFlow(emptyList())
    override fun save(goal: Goal) = Unit
    override fun remove(id: String) = Unit
}
