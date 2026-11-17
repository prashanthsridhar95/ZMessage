package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which senders / receivers (by [com.zyke.message.domain.categories.CategoryKey]) the person has chosen a picture for. */
interface SenderImageRepository {
    val keysWithImage: StateFlow<Set<String>>

    /**
     * Renames the chosen pictures kept under the first key of each pair to the second ([com.zyke.message.domain.categories.LegacyKeyMigration]): the file moves,
     * the key list follows, one published change. Where the second key already has a picture it stays and the first is dropped.
     */
    fun moveKeys(moves: Map<String, String>) = Unit
}

object NoSenderImages : SenderImageRepository {
    override val keysWithImage: StateFlow<Set<String>> = MutableStateFlow(emptySet())
}
