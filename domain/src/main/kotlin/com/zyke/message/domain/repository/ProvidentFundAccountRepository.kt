package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Accounts the person has marked as a provident fund, by account key; they are shown under Savings, not Accounts. */
interface ProvidentFundAccountRepository {
    val keys: StateFlow<Set<String>>

    fun set(key: String, isProvidentFund: Boolean)
}

object NoProvidentFundAccounts : ProvidentFundAccountRepository {
    override val keys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun set(key: String, isProvidentFund: Boolean) = Unit
}
