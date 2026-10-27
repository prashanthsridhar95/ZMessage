package com.zyke.message.domain.repository

import com.zyke.message.domain.model.UserMessageState
import kotlinx.coroutines.flow.StateFlow

interface UserMessageStateRepository {
    val state: StateFlow<UserMessageState>

    fun markRead(messageId: Long)
    fun setArchived(messageId: Long, archived: Boolean)
    fun setPinned(messageId: Long, pinned: Boolean)
    fun dismissAttention(key: String)

    /** Forgets every read / archived / pinned / dismissed mark. */
    fun clearAll()
}
