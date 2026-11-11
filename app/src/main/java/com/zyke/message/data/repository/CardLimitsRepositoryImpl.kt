package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.cards.CardLimits
import com.zyke.message.domain.cards.CardLimitsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The monthly spending limit per card, in the private preference file `card_limits` (account key to whole rupees). It is the person's
 * own setting and travels in a backup; it holds no card number beyond the account key's last four digits.
 */
class CardLimitsRepositoryImpl(context: Context) : CardLimitsRepository {
    private val prefs = context.applicationContext.getSharedPreferences("card_limits", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _limits = MutableStateFlow(read())
    override val limits: StateFlow<Map<String, Long>> = _limits.asStateFlow()

    override fun set(key: String, limit: Long) {
        synchronized(lock) {
            val clean = CardLimits.clamp(limit)
            prefs.edit().putLong(key, clean).apply()
            _limits.value = _limits.value + (key to clean)
        }
    }

    override fun clear(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).apply()
            _limits.value = _limits.value - key
        }
    }

    private fun read(): Map<String, Long> = runCatching {
        prefs.all.mapNotNull { (key, value) -> (value as? Long)?.takeIf { it > 0L }?.let { key to it } }.toMap()
    }.getOrDefault(emptyMap())
}
