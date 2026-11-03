package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.insights.RecurringGroup
import com.zyke.message.domain.repository.RecurringGroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Groups of repeating payments, the person's own names for entries and the group suggestions turned down, in the
 * private preferences file "recurring_groups" (as JSON text). The backup copies every preference file, so this goes
 * with it.
 */
class RecurringGroupRepositoryImpl(context: Context) : RecurringGroupRepository {

    private val prefs = context.applicationContext.getSharedPreferences("recurring_groups", Context.MODE_PRIVATE)

    private val _groups = MutableStateFlow(readGroups())
    override val groups: StateFlow<List<RecurringGroup>> = _groups.asStateFlow()

    private val _names = MutableStateFlow(readNames())
    override val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    private val _dismissed = MutableStateFlow(readDismissed())
    override val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    private fun readGroups(): List<RecurringGroup> = runCatching {
        val array = JSONArray(prefs.getString(GROUPS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val keys = o.optJSONArray("keys")?.let { m -> (0 until m.length()).map { m.getString(it) } }.orEmpty()
            RecurringGroup(id, o.optString("name"), keys, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun readNames(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(NAMES, "{}"))
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    private fun readDismissed(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(DISMISSED, "[]"))
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    @Synchronized
    override fun setGroups(groups: List<RecurringGroup>) {
        _groups.value = groups
        val array = JSONArray()
        groups.forEach { g ->
            array.put(JSONObject().put("id", g.id).put("name", g.name).put("keys", JSONArray(g.memberKeys)).put("at", g.createdAt))
        }
        prefs.edit().putString(GROUPS, array.toString()).apply()
    }

    @Synchronized
    override fun setNames(names: Map<String, String>) {
        _names.value = names
        val o = JSONObject()
        names.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(NAMES, o.toString()).apply()
    }

    @Synchronized
    override fun dismiss(suggestionId: String) = writeDismissed(_dismissed.value + suggestionId)

    @Synchronized
    override fun undismiss(suggestionId: String) = writeDismissed(_dismissed.value - suggestionId)

    private fun writeDismissed(ids: Set<String>) {
        _dismissed.value = ids
        prefs.edit().putString(DISMISSED, JSONArray(ids.toList()).toString()).apply()
    }

    private companion object {
        const val GROUPS = "groups"
        const val NAMES = "names"
        const val DISMISSED = "dismissed"
    }
}
