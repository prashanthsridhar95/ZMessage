package com.zyke.message.domain.repository

import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import kotlinx.coroutines.flow.StateFlow

/** Monthly spending limits per category. */
interface BudgetRepository {
    val budgets: StateFlow<Map<MessageCategory, BigDecimal>>

    /** Passing null removes the budget. */
    fun setBudget(category: MessageCategory, amount: BigDecimal?)

    /** Removes every budget. */
    fun clearAll()
}
