package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.UserCard
import com.zyke.message.domain.repository.CardRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
        val array = JSONArray()
        cards.forEach {
            array.put(
                JSONObject().put("id", it.id).put("type", it.type.name).put("last4", it.last4)
                    .put("name", it.name).put("statementDay", it.statementDay ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<UserCard> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return@runCatching emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            val type = CardType.entries.firstOrNull { t -> t.name == o.optString("type") } ?: return@mapNotNull null
            UserCard(
                id = o.getString("id"),
                type = type,
                last4 = o.getString("last4"),
                name = o.optString("name"),
                statementDay = if (o.isNull("statementDay")) null else o.getInt("statementDay")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "cards"
    }
}
