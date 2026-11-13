package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.ReceivedRefundRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The refunds the person marked as received, in private preferences as key to the day (epoch day). The backup copies
 * every preference file, so this goes with it; in Demo mode the preferences are in memory.
 */
class ReceivedRefundRepositoryImpl(context: Context) : ReceivedRefundRepository {

    private val prefs = context.applicationContext.getSharedPreferences("received_refunds", Context.MODE_PRIVATE)

    private val _received = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to LocalDate.ofEpochDay(it) } }.toMap()
    )
    override val received: StateFlow<Map<String, LocalDate>> = _received.asStateFlow()

    @Synchronized
    override fun markReceived(key: String, on: LocalDate) {
        _received.value = _received.value + (key to on)
        prefs.edit().putLong(key, on.toEpochDay()).apply()
    }

    @Synchronized
    override fun clear(key: String) {
        _received.value = _received.value - key
        prefs.edit().remove(key).apply()
    }
}
