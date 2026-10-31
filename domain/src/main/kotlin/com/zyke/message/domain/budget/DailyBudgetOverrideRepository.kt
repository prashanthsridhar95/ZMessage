package com.zyke.message.domain.budget

import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The daily budget the person chose themselves; null while they have not (the plan or past spending then suggest one). */
interface DailyBudgetOverrideRepository {
    val amount: StateFlow<BigDecimal?>

    /** A positive amount sets it; null or zero goes back to the suggestion. */
    fun set(amount: BigDecimal?)
}

object NoDailyBudgetOverride : DailyBudgetOverrideRepository {
    override val amount: StateFlow<BigDecimal?> = MutableStateFlow(null)
    override fun set(amount: BigDecimal?) = Unit
}
