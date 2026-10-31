package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.notes.PaymentNotes
import com.zyke.message.domain.repository.PaymentNotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The person's notes and tags on single payments, one JSON value per payment in private preferences. */
class PaymentNotesRepositoryImpl(context: Context) : PaymentNotesRepository {

    private val prefs = context.applicationContext.getSharedPreferences("payment_notes", Context.MODE_PRIVATE)

    private val _notes = MutableStateFlow(load())
    override val notes: StateFlow<Map<String, PaymentNotes>> = _notes.asStateFlow()

    override fun set(key: String, value: PaymentNotes?) {
        val clean = value?.let { PaymentNotes.of(it.note, it.tags) }
        val editor = prefs.edit()
        if (clean == null || clean.isEmpty) editor.remove(key) else editor.putString(key, encode(clean))
        editor.apply()
        _notes.value = if (clean == null || clean.isEmpty) _notes.value - key else _notes.value + (key to clean)
        com.zyke.message.data.diag.DevLog.d("notes") { "set count ${_notes.value.size} tags=${clean?.tags?.size ?: 0}" }
    }

    private fun encode(value: PaymentNotes): String =
        JSONObject().put("n", value.note).put("t", JSONArray(value.tags.toList())).toString()

    private fun load(): Map<String, PaymentNotes> = buildMap {
        prefs.all.forEach { (key, raw) ->
            val json = (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            val tags = json.optJSONArray("t")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
            val notes = PaymentNotes.of(json.optString("n"), tags)
            if (!notes.isEmpty) put(key, notes)
        }
    }
}
