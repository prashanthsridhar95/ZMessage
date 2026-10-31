package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.budget.DailyBudgetOverrideRepository
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The daily budget the person set, kept in private preferences (and so in the backup, which copies them all). */
class DailyBudgetOverrideRepositoryImpl(context: Context) : DailyBudgetOverrideRepository {

    private val prefs = context.applicationContext.getSharedPreferences("daily_budget", Context.MODE_PRIVATE)

    private val _amount = MutableStateFlow(prefs.getString(KEY, null)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 })
    override val amount: StateFlow<BigDecimal?> = _amount.asStateFlow()

    @Synchronized
    override fun set(amount: BigDecimal?) {
        val value = amount?.takeIf { it.signum() > 0 }
        _amount.value = value
        prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value.toPlainString()) }.apply()
    }

    private companion object {
        const val KEY = "amount"
    }
}
