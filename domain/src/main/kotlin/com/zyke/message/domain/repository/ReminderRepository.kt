package com.zyke.message.domain.repository

import com.zyke.message.domain.reminders.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ReminderRepository {
    val reminders: StateFlow<List<Reminder>>

    /** Adds the reminder, or replaces the one with the same id. */
    fun save(reminder: Reminder)

    fun remove(id: String)
}

object NoReminders : ReminderRepository {
    override val reminders: StateFlow<List<Reminder>> = MutableStateFlow(emptyList())
    override fun save(reminder: Reminder) = Unit
    override fun remove(id: String) = Unit
}
