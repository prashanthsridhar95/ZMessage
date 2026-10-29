package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.repository.CalendarEventRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The events the person put on the calendar, kept in private preferences. */
class CalendarEventRepositoryImpl(context: Context) : CalendarEventRepository {

    private val prefs = context.applicationContext.getSharedPreferences("calendar_events", Context.MODE_PRIVATE)

    private val _events = MutableStateFlow(load())
    override val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    @Synchronized
    override fun save(event: CalendarEvent) {
        val exists = _events.value.any { it.id == event.id }
        write(if (exists) _events.value.map { if (it.id == event.id) event else it } else _events.value + event)
    }

    @Synchronized
    override fun remove(id: String) = write(_events.value.filter { it.id != id })

    private fun write(events: List<CalendarEvent>) {
        _events.value = events
        val array = JSONArray()
        events.forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("date", it.date.toString())
                    .put("time", it.time?.toString() ?: JSONObject.NULL).put("note", it.note)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<CalendarEvent> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            CalendarEvent(
                id = o.getString("id"),
                title = o.getString("title"),
                date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull() ?: return@mapNotNull null,
                time = if (o.isNull("time")) null else runCatching { LocalTime.parse(o.getString("time")) }.getOrNull(),
                note = o.optString("note")
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "events"
    }
}
