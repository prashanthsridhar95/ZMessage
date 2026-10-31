package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.networth.NetWorthKind
import com.zyke.message.domain.repository.NetWorthRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The assets and debts the person entered for net worth, kept in private preferences. */
class NetWorthRepositoryImpl(context: Context) : NetWorthRepository {

    private val prefs = context.applicationContext.getSharedPreferences("net_worth", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    override val items: StateFlow<List<NetWorthItem>> = _items.asStateFlow()

    override fun save(item: NetWorthItem) {
        val exists = _items.value.any { it.id == item.id }
        write(if (exists) _items.value.map { if (it.id == item.id) item else it } else _items.value + item)
    }

    override fun remove(id: String) = write(_items.value.filter { it.id != id })

    private fun write(items: List<NetWorthItem>) {
        _items.value = items
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)
                    .put("amount", it.amount.toPlainString()).put("asOf", it.asOf.toString()).put("note", it.note)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<NetWorthItem> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            NetWorthItem(
                id = o.getString("id"),
                name = o.getString("name"),
                kind = runCatching { NetWorthKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null,
                amount = o.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null,
                asOf = runCatching { LocalDate.parse(o.getString("asOf")) }.getOrNull() ?: return@mapNotNull null,
                note = o.optString("note")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "items"
    }
}
