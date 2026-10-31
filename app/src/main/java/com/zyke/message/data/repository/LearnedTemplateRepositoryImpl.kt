package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.LearnedTemplateRepository
import com.zyke.message.domain.teach.LearnedTemplate
import com.zyke.message.domain.teach.TemplateGroups
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Message formats the person taught, kept as masked patterns (never message text) in private preferences, as JSON. */
class LearnedTemplateRepositoryImpl(context: Context) : LearnedTemplateRepository {

    private val prefs = context.applicationContext.getSharedPreferences("learned_templates", Context.MODE_PRIVATE)

    private val _templates = MutableStateFlow(load())
    override val templates: StateFlow<List<LearnedTemplate>> = _templates.asStateFlow()

    @Synchronized
    override fun add(template: LearnedTemplate, index: Int) {
        val rest = _templates.value.filterNot { it.id == template.id }
        save(rest.toMutableList().also { it.add(index.coerceIn(0, it.size), template) })
    }

    @Synchronized
    override fun remove(id: String) {
        save(_templates.value.filterNot { it.id == id })
    }

    private fun save(list: List<LearnedTemplate>) {
        _templates.value = list
        val array = JSONArray()
        list.forEach { t ->
            array.put(
                JSONObject()
                    .put("id", t.id).put("sender", t.senderKey).put("pattern", t.pattern)
                    .put("amount", t.groups.amount).put("payee", t.groups.payee ?: JSONObject.NULL).put("account", t.groups.account ?: JSONObject.NULL)
                    .put("received", t.isReceived).put("category", t.category?.name ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        com.zyke.message.data.diag.DevLog.d("teach") { "templates saved count=${list.size}" }
    }

    private fun load(): List<LearnedTemplate> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                LearnedTemplate(
                    id = o.getString("id"),
                    senderKey = o.getString("sender"),
                    pattern = o.getString("pattern"),
                    groups = TemplateGroups(
                        amount = o.getInt("amount"),
                        payee = if (o.isNull("payee")) null else o.getInt("payee"),
                        account = if (o.isNull("account")) null else o.getInt("account")
                    ),
                    isReceived = o.getBoolean("received"),
                    category = if (o.isNull("category")) null else runCatching { MessageCategory.valueOf(o.getString("category")) }.getOrNull()
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY = "templates"
    }
}
