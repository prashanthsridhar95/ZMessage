package com.zyke.message.domain.repository

import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Spending limits per category: a monthly one, a daily one, or both. */
interface BudgetRepository {
    /** The monthly limits. */
    val budgets: StateFlow<Map<MessageCategory, BigDecimal>>

    /** Passing null removes the monthly limit. */
    fun setBudget(category: MessageCategory, amount: BigDecimal?)

    /** The daily limits. */
    val dailyBudgets: StateFlow<Map<MessageCategory, BigDecimal>> get() = NO_LIMITS

    /** Passing null removes the daily limit. */
    fun setDailyBudget(category: MessageCategory, amount: BigDecimal?) = Unit

    /** Removes every limit. */
    fun clearAll()

    private companion object {
        val NO_LIMITS: StateFlow<Map<MessageCategory, BigDecimal>> = MutableStateFlow(emptyMap())
    }
}
