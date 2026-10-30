package com.zyke.message.data.export

import com.zyke.message.di.AppContainer
import org.json.JSONArray
import org.json.JSONObject

/** The parts of the person's own setup that the settings screen does not hold, for the export file. */
object SetupExport {
    fun extras(container: AppContainer): JSONObject = JSONObject().apply {
        put("goals", JSONArray().apply {
            container.goals.goals.value.forEach {
                put(JSONObject().put("name", it.name).put("target", it.target.toPlainString()).put("saved", it.saved.toPlainString()).put("targetDate", it.targetDate?.toString()))
            }
        })
        put("reminders", JSONArray().apply {
            container.reminders.reminders.value.forEach {
                put(JSONObject().put("title", it.title).put("amount", it.amount?.toPlainString()).put("date", it.date.toString()).put("monthly", it.repeatsMonthly).put("time", it.time?.toString()))
            }
        })
        put("events", JSONArray().apply {
            container.calendarEvents.events.value.forEach {
                put(JSONObject().put("title", it.title).put("date", it.date.toString()).put("time", it.time?.toString()).put("note", it.note))
            }
        })
        put("entries", JSONArray().apply {
            container.manualEntries.entries.value.forEach {
                put(JSONObject().put("kind", it.kind.name).put("amount", it.amount.toPlainString()).put("title", it.title).put("category", it.category.displayString).put("date", it.date.toString()).put("note", it.note).put("account", it.account))
            }
        })
    }
}
