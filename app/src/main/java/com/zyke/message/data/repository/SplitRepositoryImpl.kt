package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.SplitRepository
import com.zyke.message.domain.split.Split
import com.zyke.message.domain.split.SplitPayer
import com.zyke.message.domain.split.SplitPerson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class SplitRepositoryImpl(context: Context) : SplitRepository {

    private val prefs = context.applicationContext.getSharedPreferences("splits", Context.MODE_PRIVATE)

    private val _splits = MutableStateFlow(load())
    override val splits: StateFlow<List<Split>> = _splits.asStateFlow()

    override fun save(split: Split) {
        val exists = _splits.value.any { it.id == split.id }
        update(if (exists) _splits.value.map { if (it.id == split.id) split else it } else _splits.value + split)
    }

    override fun remove(id: String) = update(_splits.value.filter { it.id != id })

    private fun update(splits: List<Split>) {
        _splits.value = splits
        val array = JSONArray()
        splits.forEach { split ->
            val people = JSONArray()
            split.people.forEach { person ->
                people.put(
                    JSONObject().put("name", person.name).put("share", person.share.toPlainString()).put("settled", person.settledByHand)
                )
            }
            array.put(
                JSONObject()
                    .put("id", split.id).put("message", split.messageId).put("title", split.title)
                    .put("total", split.total.toPlainString()).put("people", people).put("paidBy", split.paidBy.name)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<Split> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val json = array.getJSONObject(index)
            val total = json.optString("total").toBigDecimalOrNull() ?: return@mapNotNull null
            val peopleJson = json.getJSONArray("people")
            val people = (0 until peopleJson.length()).mapNotNull { i ->
                val person = peopleJson.getJSONObject(i)
                val share = person.optString("share").toBigDecimalOrNull() ?: return@mapNotNull null
                SplitPerson(person.getString("name"), share, person.optBoolean("settled"))
            }
            // Splits saved before "who paid" existed have no paidBy; they were all paid by the person.
            Split(json.getString("id"), json.getLong("message"), json.getString("title"), total, people, SplitPayer.parse(json.optString("paidBy", null)))
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "splits"
    }
}
