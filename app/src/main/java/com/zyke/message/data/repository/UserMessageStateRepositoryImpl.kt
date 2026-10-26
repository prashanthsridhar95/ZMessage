package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
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

    override fun dismissAttention(key: String) = change { copy(dismissedAttention = dismissedAttention + key) }

    private fun change(transform: UserMessageState.() -> UserMessageState) {
        _state.update { it.transform() }
        save(_state.value)
    }

    private fun load() = UserMessageState(
        readIds = longs(READ),
        archivedIds = longs(ARCHIVED),
        pinnedIds = longs(PINNED),
        dismissedAttention = prefs.getStringSet(DISMISSED, emptySet()).orEmpty().toSet()
    )

    private fun longs(key: String): Set<Long> =
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun save(state: UserMessageState) {
        prefs.edit()
            .putStringSet(READ, state.readIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(ARCHIVED, state.archivedIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(PINNED, state.pinnedIds.mapTo(HashSet()) { it.toString() })
            .putStringSet(DISMISSED, HashSet(state.dismissedAttention))
            .apply()
    }

    private companion object {
        const val FILE = "user_message_state"
        const val READ = "read"
        const val ARCHIVED = "archived"
        const val PINNED = "pinned"
        const val DISMISSED = "dismissed_attention"
    }
}
