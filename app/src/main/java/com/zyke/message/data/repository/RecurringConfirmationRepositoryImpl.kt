package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.RecurringConfirmationRepository
import com.zyke.message.domain.repository.RecurringState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The person's answers about recurring payments, kept in private preferences as key to state name. */
class RecurringConfirmationRepositoryImpl(context: Context) : RecurringConfirmationRepository {

    private val prefs = context.applicationContext.getSharedPreferences("recurring_payments", Context.MODE_PRIVATE)

    private val _states = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) ->
            val state = (value as? String)?.let { name -> RecurringState.values().firstOrNull { it.name == name } }
            state?.let { key to it }
        }.toMap()
    )
    override val states: StateFlow<Map<String, RecurringState>> = _states.asStateFlow()

    @Synchronized
    override fun set(key: String, state: RecurringState) {
        _states.value = _states.value + (key to state)
        prefs.edit().putString(key, state.name).apply()
    }

    @Synchronized
    override fun clear(key: String) {
        _states.value = _states.value - key
        prefs.edit().remove(key).apply()
    }
}
