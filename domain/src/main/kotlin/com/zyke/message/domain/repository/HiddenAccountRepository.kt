package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Accounts and cards the person says are not theirs, by account key; they are left out of the accounts screen. */
interface HiddenAccountRepository {
    val hidden: StateFlow<Set<String>>

    fun hide(key: String)

    fun show(key: String)
}

object NoHiddenAccounts : HiddenAccountRepository {
    override val hidden: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun hide(key: String) = Unit
    override fun show(key: String) = Unit
}
