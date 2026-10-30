package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.ledger.ManualEntry
import com.zyke.message.domain.ledger.ManualEntryRepository
import com.zyke.message.domain.ledger.ManualKind
import com.zyke.message.domain.model.MessageCategory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What the person recorded by hand, in private preferences. */
class ManualEntryRepositoryImpl(context: Context) : ManualEntryRepository {
    private val prefs = context.applicationContext.getSharedPreferences("manual_entries", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    override val entries: StateFlow<List<ManualEntry>> = _entries.asStateFlow()

    override fun save(entry: ManualEntry) {
        val exists = _entries.value.any { it.id == entry.id }
        write(if (exists) _entries.value.map { if (it.id == entry.id) entry else it } else _entries.value + entry)
    }

    override fun remove(id: Long) = write(_entries.value.filter { it.id != id })

    override fun nextId(): Long = (_entries.value.maxOfOrNull { it.id } ?: 0L) + 1

    private fun write(entries: List<ManualEntry>) {
        _entries.value = entries
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject().put("id", it.id).put("kind", it.kind.name).put("amount", it.amount.toPlainString()).put("title", it.title)
                    .put("category", it.category.name).put("date", it.date.toString()).put("note", it.note).put("account", it.account)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<ManualEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            ManualEntry(
                id = o.getLong("id"),
                kind = ManualKind.entries.firstOrNull { k -> k.name == o.optString("kind") } ?: return@mapNotNull null,
                amount = o.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null,
                title = o.optString("title"),
                category = MessageCategory.entries.firstOrNull { c -> c.name == o.optString("category") } ?: MessageCategory.OTHERS,
                date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null,
                note = o.optString("note"),
                account = o.optString("account", "Cash")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "entries"
    }
}
