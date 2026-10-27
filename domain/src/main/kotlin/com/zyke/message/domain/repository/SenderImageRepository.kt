package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which senders / receivers (by [com.zyke.message.domain.categories.CategoryKey]) the person has chosen a picture for. */
interface SenderImageRepository {
    val keysWithImage: StateFlow<Set<String>>
}

object NoSenderImages : SenderImageRepository {
    override val keysWithImage: StateFlow<Set<String>> = MutableStateFlow(emptySet())
}
