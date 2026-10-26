package com.zyke.message.domain.model

/** Things the user did in this app; the SMS provider itself is never written to. */
data class UserMessageState(
    val readIds: Set<Long> = emptySet(),
    val archivedIds: Set<Long> = emptySet(),
    val pinnedIds: Set<Long> = emptySet(),
    val dismissedAttention: Set<String> = emptySet()
)
