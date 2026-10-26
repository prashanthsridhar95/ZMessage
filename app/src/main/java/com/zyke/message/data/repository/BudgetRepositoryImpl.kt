package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.BudgetRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BudgetRepositoryImpl(context: Context) : BudgetRepository {

    private val prefs = context.applicationContext.getSharedPreferences("budgets", Context.MODE_PRIVATE)

    private val _budgets = MutableStateFlow(load())
    override val budgets: StateFlow<Map<MessageCategory, BigDecimal>> = _budgets.asStateFlow()

    override fun setBudget(category: MessageCategory, amount: BigDecimal?) {
        val editor = prefs.edit()
        if (amount == null) editor.remove(category.name) else editor.putString(category.name, amount.toPlainString())
        editor.apply()
        _budgets.value = if (amount == null) _budgets.value - category else _budgets.value + (category to amount)
    }

    private fun load(): Map<MessageCategory, BigDecimal> =
        MessageCategory.entries.mapNotNull { category ->
            prefs.getString(category.name, null)?.toBigDecimalOrNull()?.let { category to it }
        }.toMap()
}
