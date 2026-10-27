package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.BudgetRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BudgetRepositoryImpl(context: Context) : BudgetRepository {

    private val prefs = context.applicationContext.getSharedPreferences("budgets", Context.MODE_PRIVATE)
    private val dailyPrefs = context.applicationContext.getSharedPreferences("daily_budgets", Context.MODE_PRIVATE)

    private val _budgets = MutableStateFlow(load(prefs))
    override val budgets: StateFlow<Map<MessageCategory, BigDecimal>> = _budgets.asStateFlow()

    private val _daily = MutableStateFlow(load(dailyPrefs))
    override val dailyBudgets: StateFlow<Map<MessageCategory, BigDecimal>> = _daily.asStateFlow()

    override fun setBudget(category: MessageCategory, amount: BigDecimal?) {
        write(prefs, category, amount)
        _budgets.value = if (amount == null) _budgets.value - category else _budgets.value + (category to amount)
    }

    override fun setDailyBudget(category: MessageCategory, amount: BigDecimal?) {
        write(dailyPrefs, category, amount)
        _daily.value = if (amount == null) _daily.value - category else _daily.value + (category to amount)
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
        dailyPrefs.edit().clear().apply()
        _budgets.value = emptyMap()
        _daily.value = emptyMap()
    }

    private fun write(store: SharedPreferences, category: MessageCategory, amount: BigDecimal?) {
        val editor = store.edit()
        if (amount == null) editor.remove(category.name) else editor.putString(category.name, amount.toPlainString())
        editor.apply()
    }

    private fun load(store: SharedPreferences): Map<MessageCategory, BigDecimal> =
        MessageCategory.entries.mapNotNull { category ->
            store.getString(category.name, null)?.toBigDecimalOrNull()?.let { category to it }
        }.toMap()
}
