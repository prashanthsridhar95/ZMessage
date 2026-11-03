package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.insights.RecurringMerge
import com.zyke.message.domain.repository.RecurringMergeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges of repeating payments and the suggestions turned down, in the private preferences file "recurring_merges"
 * (as JSON text). The backup copies every preference file, so this goes with it.
 */
class RecurringMergeRepositoryImpl(context: Context) : RecurringMergeRepository {

    private val prefs = context.applicationContext.getSharedPreferences("recurring_merges", Context.MODE_PRIVATE)

    private val _merges = MutableStateFlow(readMerges())
    override val merges: StateFlow<List<RecurringMerge>> = _merges.asStateFlow()

    private val _dismissed = MutableStateFlow(readDismissed())
    override val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    private fun readMerges(): List<RecurringMerge> = runCatching {
        val array = JSONArray(prefs.getString(MERGES, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val primary = o.optString("primary").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val members = o.optJSONArray("members")?.let { m -> (0 until m.length()).map { m.getString(it) } }.orEmpty()
            RecurringMerge(primary, members, o.optString("name").takeIf { it.isNotEmpty() }, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun readDismissed(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(DISMISSED, "[]"))
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    @Synchronized
    override fun setAll(merges: List<RecurringMerge>) {
        _merges.value = merges
        val array = JSONArray()
        merges.forEach { m ->
            array.put(
                JSONObject().put("primary", m.primaryKey).put("members", JSONArray(m.memberKeys))
                    .put("name", m.displayName ?: "").put("at", m.createdAt)
            )
        }
        prefs.edit().putString(MERGES, array.toString()).apply()
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
        const val MERGES = "merges"
        const val DISMISSED = "dismissed"
    }
}
