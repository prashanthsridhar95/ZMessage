package com.zyke.message.domain.repository

import com.zyke.message.domain.model.UserCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CardRepository {
    val cards: StateFlow<List<UserCard>>

    /** Adds the card, or replaces the one with the same id. */
    fun save(card: UserCard)

    fun remove(id: String)

    fun clearAll()
}

/** For callers that have no stored cards. */
object NoCards : CardRepository {
    override val cards: StateFlow<List<UserCard>> = MutableStateFlow(emptyList())
    override fun save(card: UserCard) = Unit
    override fun remove(id: String) = Unit
    override fun clearAll() = Unit
}
