package com.zyke.message.domain.repository

import com.zyke.message.domain.split.Split
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface SplitRepository {
    val splits: StateFlow<List<Split>>

    /** Adds the split, or replaces the one with the same id. */
    fun save(split: Split)

    fun remove(id: String)
}

object NoSplits : SplitRepository {
    override val splits: StateFlow<List<Split>> = MutableStateFlow(emptyList())
    override fun save(split: Split) = Unit
    override fun remove(id: String) = Unit
}
