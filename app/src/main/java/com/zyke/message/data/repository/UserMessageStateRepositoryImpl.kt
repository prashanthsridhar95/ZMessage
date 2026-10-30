package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.feed.Dismissals
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.repository.UserMessageStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Keeps read / archived / pinned marks in SharedPreferences. */
class UserMessageStateRepositoryImpl(context: Context) : UserMessageStateRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<UserMessageState> = _state.asStateFlow()

    override fun markRead(messageId: Long) = change { copy(readIds = readIds + messageId) }

    override fun setArchived(messageId: Long, archived: Boolean) = change {
        copy(archivedIds = if (archived) archivedIds + messageId else archivedIds - messageId)
    }

    override fun setPinned(messageId: Long, pinned: Boolean) = change {
        copy(pinnedIds = if (pinned) pinnedIds + messageId else pinnedIds - messageId)
    }

    override fun dismissAttention(key: String, forever: Boolean) = change {
        if (Dismissals.isTimed(key) && !forever) copy(dismissedAt = dismissedAt + (key to System.currentTimeMillis()))
        else copy(dismissedAttention = dismissedAttention + key)
    }

    override fun restoreAttention(key: String) = change {
        copy(dismissedAttention = dismissedAttention - key, dismissedAt = dismissedAt - key)
    }

    override fun clearAll() {
        _state.value = UserMessageState()
        prefs.edit().clear().apply()
    }

    override fun retainOnly(existingIds: Set<Long>) {
        val current = _state.value
        val stale = (current.readIds + current.archivedIds + current.pinnedIds).any { it !in existingIds }
        if (!stale) return
        change {
            copy(
                readIds = readIds.filterTo(HashSet()) { it in existingIds },
                archivedIds = archivedIds.filterTo(HashSet()) { it in existingIds },
                pinnedIds = pinnedIds.filterTo(HashSet()) { it in existingIds }
            )
        }
    }

    private fun change(transform: UserMessageState.() -> UserMessageState) {
        _state.update { it.transform() }
        save(_state.value)
    }

    private fun load() = UserMessageState(
        readIds = longs(READ),
        archivedIds = longs(ARCHIVED),
        pinnedIds = longs(PINNED),
        dismissedAttention = prefs.getStringSet(DISMISSED, emptySet()).orEmpty().toSet(),
        dismissedAt = runCatching {
            val json = org.json.JSONObject(prefs.getString(DISMISSED_AT, null) ?: return@runCatching emptyMap<String, Long>())
            json.keys().asSequence().associateWith { json.getLong(it) }
        }.getOrDefault(emptyMap())
    )

    private fun longs(key: String): Set<Long> =
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun save(state: UserMessageState) {
        prefs.edit()
            .putStringSet(READ, state.readIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(ARCHIVED, state.archivedIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(PINNED, state.pinnedIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(DISMISSED, HashSet(state.dismissedAttention))
            .putString(DISMISSED_AT, org.json.JSONObject().also { json -> state.dismissedAt.forEach { (k, v) -> json.put(k, v) } }.toString())
            .apply()
    }

    private companion object {
        const val FILE = "user_message_state"
        const val READ = "read"
        const val ARCHIVED = "archived"
        const val PINNED = "pinned"
        const val DISMISSED = "dismissed_attention"
        const val DISMISSED_AT = "dismissed_at"
    }
}
