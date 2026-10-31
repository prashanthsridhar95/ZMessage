package com.zyke.message.domain.repository

import com.zyke.message.domain.dashboard.BudgetAdjustments
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Budgets per category: a monthly one, a daily one, or both, and the carry-over and moves between them. The one store
 * for all of it (it replaces the separate envelope store).
 */
interface BudgetRepository {
    /** The monthly limits. */
    val budgets: StateFlow<Map<MessageCategory, BigDecimal>>

    /** Passing null removes the monthly limit. */
    fun setBudget(category: MessageCategory, amount: BigDecimal?)

    /** The daily limits. */
    val dailyBudgets: StateFlow<Map<MessageCategory, BigDecimal>> get() = NO_LIMITS

    /** Passing null removes the daily limit. */
    fun setDailyBudget(category: MessageCategory, amount: BigDecimal?) = Unit

    /** Carry-over choices and money moved between budgets. */
    val adjustments: StateFlow<BudgetAdjustments> get() = NO_ADJUSTMENTS

    fun saveAdjustments(adjustments: BudgetAdjustments) = Unit

    /** Removes every limit. */
    fun clearAll()

    private companion object {
        val NO_LIMITS: StateFlow<Map<MessageCategory, BigDecimal>> = MutableStateFlow(emptyMap())
        val NO_ADJUSTMENTS: StateFlow<BudgetAdjustments> = MutableStateFlow(BudgetAdjustments())
    }
}
