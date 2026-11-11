package com.zyke.message.domain.cards

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The monthly spending limit the person set for each card, by account key (whole rupees). Nothing here is read from a message. */
interface CardLimitsRepository {
    val limits: StateFlow<Map<String, Long>>
    fun set(key: String, limit: Long)
    fun clear(key: String)
}

/** Nothing is kept and nothing can be set. */
object NoCardLimits : CardLimitsRepository {
    override val limits: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override fun set(key: String, limit: Long) = Unit
    override fun clear(key: String) = Unit
}

/** Demo mode and tests: kept in memory only. */
class InMemoryCardLimits : CardLimitsRepository {
    private val _limits = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val limits: StateFlow<Map<String, Long>> = _limits
    override fun set(key: String, limit: Long) { _limits.value = _limits.value + (key to CardLimits.clamp(limit)) }
    override fun clear(key: String) { _limits.value = _limits.value - key }
}
