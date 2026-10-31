package com.zyke.message.domain.repository

import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.networth.NetWorthItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The assets and debts the person typed in, kept on the phone. */
interface NetWorthRepository {
    val items: StateFlow<List<NetWorthItem>>

    /** Adds the entry, or replaces the one with the same id. */
    fun save(item: NetWorthItem)

    fun remove(id: String)
}

object NoNetWorth : NetWorthRepository {
    override val items: StateFlow<List<NetWorthItem>> = MutableStateFlow(emptyList())
    override fun save(item: NetWorthItem) = Unit
    override fun remove(id: String) = Unit
}

/** The loans the person typed in, kept on the phone. */
interface LoanRepository {
    val loans: StateFlow<List<Loan>>

    /** Adds the loan, or replaces the one with the same id. */
    fun save(loan: Loan)

    fun remove(id: String)
}

object NoLoans : LoanRepository {
    override val loans: StateFlow<List<Loan>> = MutableStateFlow(emptyList())
    override fun save(loan: Loan) = Unit
    override fun remove(id: String) = Unit
}
