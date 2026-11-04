package com.zyke.message.domain.investments

import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.networth.NetWorthKind
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything kept about investments. */
data class Holdings(
    val holdings: List<Holding> = emptyList(),
    /** Net-worth entries already copied into [holdings]; they are hidden from the net-worth page and its total. */
    val migratedItemIds: Set<String> = emptySet(),
    /** Holdings whose "matured: renew, close or keep?" card was dismissed. */
    val maturityDismissed: Set<String> = emptySet()
) {
    fun find(id: String): Holding? = holdings.firstOrNull { it.id == id }
}

/** What to put back to undo one change: the holdings as they were, and the ones that did not exist before. */
data class HoldingUndo(val restore: List<Holding>, val remove: Set<String>)

interface HoldingRepository {
    val state: StateFlow<Holdings>
    fun add(holding: Holding): HoldingUndo
    fun update(holding: Holding): HoldingUndo
    fun remove(id: String): HoldingUndo
    fun updateValue(id: String, value: BigDecimal, on: LocalDate, invested: BigDecimal? = null): HoldingUndo?
    fun markMatured(id: String): HoldingUndo?
    fun close(id: String, received: BigDecimal?, on: LocalDate): HoldingUndo?
    fun renew(id: String, successor: Holding): HoldingUndo?
    fun dismissMaturityCard(id: String)
    fun migrate(items: List<NetWorthItem>): Int
    fun undo(undo: HoldingUndo)
}

object NoHoldings : HoldingRepository {
    override val state: StateFlow<Holdings> = MutableStateFlow(Holdings())
    override fun add(holding: Holding) = HoldingUndo(emptyList(), emptySet())
    override fun update(holding: Holding) = HoldingUndo(emptyList(), emptySet())
    override fun remove(id: String) = HoldingUndo(emptyList(), emptySet())
    override fun updateValue(id: String, value: BigDecimal, on: LocalDate, invested: BigDecimal?): HoldingUndo? = null
    override fun markMatured(id: String): HoldingUndo? = null
    override fun close(id: String, received: BigDecimal?, on: LocalDate): HoldingUndo? = null
    override fun renew(id: String, successor: Holding): HoldingUndo? = null
    override fun dismissMaturityCard(id: String) = Unit
    override fun migrate(items: List<NetWorthItem>) = 0
    override fun undo(undo: HoldingUndo) = Unit
}

/**
 * The working part of the holdings repository, free of Android so it can be tested. Every change is read, applied,
 * published and saved under one lock, so two changes can never start from the same old state. Developer-log lines carry
 * ids and counts only (tag "investments").
 */
class HoldingStore(initial: Holdings = Holdings(), private val persist: (Holdings) -> Unit = {}) : HoldingRepository {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<Holdings> = _state.asStateFlow()

    override fun add(holding: Holding): HoldingUndo = change("add", setOf(holding.id)) { s ->
        s.copy(holdings = s.holdings.filter { it.id != holding.id } + holding)
    }

    override fun update(holding: Holding): HoldingUndo = change("update", setOf(holding.id)) { s ->
        s.copy(holdings = if (s.find(holding.id) == null) s.holdings + holding else s.holdings.map { if (it.id == holding.id) holding else it })
    }

    override fun remove(id: String): HoldingUndo = change("remove", setOf(id)) { s -> s.copy(holdings = s.holdings.filter { it.id != id }) }

    override fun updateValue(id: String, value: BigDecimal, on: LocalDate, invested: BigDecimal?): HoldingUndo? =
        edit(id, "value") { InvestmentMath.withValue(it, value, on, invested ?: it.invested) }

    override fun markMatured(id: String): HoldingUndo? = edit(id, "matured") { it.copy(status = HoldingStatus.MATURED) }

    override fun close(id: String, received: BigDecimal?, on: LocalDate): HoldingUndo? =
        edit(id, "close") { it.copy(status = HoldingStatus.CLOSED, closedOn = on, closedAmount = received) }

    override fun renew(id: String, successor: Holding): HoldingUndo? = synchronized(lock) {
        val old = _state.value.find(id) ?: return null
        change("renew", setOf(id, successor.id)) { s ->
            s.copy(
                holdings = s.holdings.map { if (it.id == id) it.copy(status = HoldingStatus.RENEWED, closedOn = successor.startDate) else it }
                    .filter { it.id != successor.id } + successor.copy(renewedFrom = old.id)
            )
        }
    }

    override fun dismissMaturityCard(id: String) {
        change("dismiss", emptySet()) { it.copy(maturityDismissed = it.maturityDismissed + id) }
    }

    /**
     * Copies manual net-worth entries of investment kinds into holdings, once each (idempotent). The value is carried over
     * as both the amount invested and the current value, dated as the entry was.
     */
    override fun migrate(items: List<NetWorthItem>): Int = synchronized(lock) {
        val fresh = items.filter { it.kind in MIGRATED && it.id !in _state.value.migratedItemIds }
        if (fresh.isEmpty()) return 0
        change("migrate", emptySet()) { s ->
            val ids = s.holdings.map { it.id }.toSet()
            val add = fresh.map { toHolding(it) }.filter { it.id !in ids }
            s.copy(holdings = s.holdings + add, migratedItemIds = s.migratedItemIds + fresh.map { it.id })
        }
        fresh.size
    }

    override fun undo(undo: HoldingUndo) {
        change("undo", undo.remove + undo.restore.map { it.id }) { s ->
            val kept = s.holdings.filter { it.id !in undo.remove && undo.restore.none { r -> r.id == it.id } }
            s.copy(holdings = kept + undo.restore)
        }
    }

    private fun edit(id: String, what: String, apply: (Holding) -> Holding): HoldingUndo? = synchronized(lock) {
        if (_state.value.find(id) == null) return null
        change(what, setOf(id)) { s -> s.copy(holdings = s.holdings.map { if (it.id == id) apply(it) else it }) }
    }

    private fun change(what: String, touched: Set<String>, apply: (Holdings) -> Holdings): HoldingUndo = synchronized(lock) {
        val before = _state.value
        val after = apply(before)
        _state.value = after
        persist(after)
        DiagLog.d("investments") { "$what: holdings ${before.holdings.size}->${after.holdings.size}, changed=${before != after}" }
        HoldingUndo(restore = before.holdings.filter { it.id in touched }, remove = touched.filter { id -> before.find(id) == null }.toSet())
    }

    companion object {
        val MIGRATED: Set<NetWorthKind> = setOf(
            NetWorthKind.FIXED_DEPOSIT, NetWorthKind.MUTUAL_FUNDS, NetWorthKind.STOCKS, NetWorthKind.GOLD, NetWorthKind.RETIREMENT
        )

        fun kindOf(kind: NetWorthKind): InvestmentKind = when (kind) {
            NetWorthKind.FIXED_DEPOSIT -> InvestmentKind.FIXED_DEPOSIT
            NetWorthKind.MUTUAL_FUNDS -> InvestmentKind.MUTUAL_FUND
            NetWorthKind.STOCKS -> InvestmentKind.STOCKS
            NetWorthKind.GOLD -> InvestmentKind.GOLD
            NetWorthKind.RETIREMENT -> InvestmentKind.PPF_NPS
            else -> InvestmentKind.OTHER
        }

        fun toHolding(item: NetWorthItem): Holding = Holding(
            id = "nw:${item.id}", kind = kindOf(item.kind), name = item.name, invested = item.amount,
            currentValue = item.amount, valueAsOf = item.asOf, notes = item.note
        )
    }
}
