package com.zyke.message.domain.repository

import com.zyke.message.domain.calendar.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CalendarEventRepository {
    val events: StateFlow<List<CalendarEvent>>

    /** Adds the event, or replaces the one with the same id. */
    fun save(event: CalendarEvent)

    fun remove(id: String)
}

object NoCalendarEvents : CalendarEventRepository {
    override val events: StateFlow<List<CalendarEvent>> = MutableStateFlow(emptyList())
    override fun save(event: CalendarEvent) = Unit
    override fun remove(id: String) = Unit
}
