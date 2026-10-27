package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Senders the person has muted, by [com.zyke.message.domain.conversation.SenderKey]. */
interface MutedSenderRepository {
    val muted: StateFlow<Set<String>>

    fun setMuted(senderKey: String, muted: Boolean)
}

object NoMutedSenders : MutedSenderRepository {
    override val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun setMuted(senderKey: String, muted: Boolean) = Unit
}
