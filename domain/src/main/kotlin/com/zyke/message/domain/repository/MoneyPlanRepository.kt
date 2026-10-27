package com.zyke.message.domain.repository

import com.zyke.message.domain.plan.MoneyPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface MoneyPlanRepository {
    val plan: StateFlow<MoneyPlan>

    /** Replaces the whole plan. */
    fun save(plan: MoneyPlan)

    fun clear()
}

/** For callers that have no stored plan. */
object NoMoneyPlan : MoneyPlanRepository {
    override val plan: StateFlow<MoneyPlan> = MutableStateFlow(MoneyPlan())
    override fun save(plan: MoneyPlan) = Unit
    override fun clear() = Unit
}
