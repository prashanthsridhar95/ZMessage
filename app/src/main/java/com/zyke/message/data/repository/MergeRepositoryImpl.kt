package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.merge.Merges
import com.zyke.message.domain.repository.MergeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Which messages the person has said are one transaction, kept in private preferences. */
class MergeRepositoryImpl(context: Context) : MergeRepository {

    private val prefs = context.applicationContext.getSharedPreferences("merged_messages", Context.MODE_PRIVATE)

    private val _merges = MutableStateFlow(load())
    override val merges: StateFlow<Map<Long, Long>> = _merges.asStateFlow()

    @Synchronized
    override fun merge(primaryId: Long, duplicateId: Long) = write(Merges.add(_merges.value, primaryId, duplicateId))

    @Synchronized
    override fun unmerge(duplicateId: Long) = write(Merges.remove(_merges.value, duplicateId))

    private fun write(merges: Map<Long, Long>) {
        _merges.value = merges
        val json = JSONObject()
        merges.forEach { (duplicate, primary) -> json.put(duplicate.toString(), primary) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun load(): Map<Long, Long> = runCatching {
        val json = JSONObject(prefs.getString(KEY, null) ?: return emptyMap())
        json.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to json.getLong(key) } }.toMap()
    }.getOrDefault(emptyMap())

    private companion object {
        const val KEY = "merges"
    }
}
