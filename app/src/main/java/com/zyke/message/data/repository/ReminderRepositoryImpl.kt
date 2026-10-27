package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.ReminderRepository
import com.zyke.message.domain.reminders.Reminder
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The person's reminders, kept in private preferences. */
class ReminderRepositoryImpl(context: Context) : ReminderRepository {

    private val prefs = context.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private val _reminders = MutableStateFlow(load())
    override val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    override fun save(reminder: Reminder) {
        val exists = _reminders.value.any { it.id == reminder.id }
        write(if (exists) _reminders.value.map { if (it.id == reminder.id) reminder else it } else _reminders.value + reminder)
    }

    override fun remove(id: String) = write(_reminders.value.filter { it.id != id })

    private fun write(reminders: List<Reminder>) {
        _reminders.value = reminders
        val array = JSONArray()
        reminders.forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("amount", it.amount?.toPlainString() ?: JSONObject.NULL)
                    .put("date", it.date.toString()).put("monthly", it.repeatsMonthly)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<Reminder> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            Reminder(
                id = o.getString("id"),
                title = o.getString("title"),
                amount = if (o.isNull("amount")) null else o.optString("amount").toBigDecimalOrNull(),
                date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null,
                repeatsMonthly = o.optBoolean("monthly")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "reminders"
    }
}
