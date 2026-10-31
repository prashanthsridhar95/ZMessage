package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.CancelledSubscriptionRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The subscriptions the person marked as cancelled, in private preferences as key to the day (epoch day). The backup
 * copies every preference file, so this goes with it.
 */
class CancelledSubscriptionRepositoryImpl(context: Context) : CancelledSubscriptionRepository {

    private val prefs = context.applicationContext.getSharedPreferences("cancelled_subscriptions", Context.MODE_PRIVATE)

    private val _cancelled = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to LocalDate.ofEpochDay(it) } }.toMap()
    )
    override val cancelled: StateFlow<Map<String, LocalDate>> = _cancelled.asStateFlow()

    @Synchronized
    override fun markCancelled(key: String, on: LocalDate) {
        _cancelled.value = _cancelled.value + (key to on)
        prefs.edit().putLong(key, on.toEpochDay()).apply()
    }

    @Synchronized
    override fun clear(key: String) {
        _cancelled.value = _cancelled.value - key
        prefs.edit().remove(key).apply()
    }
}
