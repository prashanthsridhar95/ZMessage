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

    /**
     * Drops the read, archived and pinned marks of messages that are no longer in the inbox, for example because
     * they were deleted in the messaging app, so this app never keeps traces of messages that are gone.
     */
    fun retainOnly(existingIds: Set<Long>) = Unit
}
