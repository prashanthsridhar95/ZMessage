package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Names the person gave to senders / receivers so they are easy to recall, by [com.zyke.message.domain.categories.CategoryKey]. */
interface NicknameRepository {
    val nicknames: StateFlow<Map<String, String>>

    /** A blank [name] removes the nickname. */
    fun set(key: String, name: String?)

    /** Many names in one change: one saved write and one published state. A null or blank name removes the nickname. */
    fun setAll(changes: Map<String, String?>) = changes.forEach { (key, name) -> set(key, name) }
}

object NoNicknames : NicknameRepository {
    override val nicknames: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override fun set(key: String, name: String?) = Unit
}
