package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.cards.UserCardCodec
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.repository.CardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The person's cards (last four digits, name, statement date), in private preferences. */
class CardRepositoryImpl(context: Context) : CardRepository {

    private val prefs = context.applicationContext.getSharedPreferences("cards", Context.MODE_PRIVATE)

    private val _cards = MutableStateFlow(load())
    override val cards: StateFlow<List<UserCard>> = _cards.asStateFlow()

    override fun save(card: UserCard) {
        val current = _cards.value
        update(if (current.any { it.id == card.id }) current.map { if (it.id == card.id) card else it } else current + card)
    }

    override fun remove(id: String) = update(_cards.value.filter { it.id != id })

    override fun clearAll() = update(emptyList())

    private fun update(cards: List<UserCard>) {
        _cards.value = cards
        prefs.edit().putString(KEY, UserCardCodec.encode(cards)).apply()
    }

    private fun load(): List<UserCard> = UserCardCodec.decode(prefs.getString(KEY, null))

    private companion object {
        const val KEY = "cards"
    }
}
