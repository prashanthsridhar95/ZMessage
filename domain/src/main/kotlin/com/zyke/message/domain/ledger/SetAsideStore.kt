package com.zyke.message.domain.ledger

import com.zyke.message.domain.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The working part of the set-aside repository, free of Android so it can be tested: every change is read, applied,
 * published and saved under one lock. Without that, a change made on the main thread (leave out) and one made on the
 * write queue (link a transfer) could each start from the same old state, and the later one would silently undo the
 * earlier. Each change writes one developer-log line (tag "ledger": ids and counts only).
 */
class SetAsideStore(initial: SetAsides = SetAsides(), private val persist: (SetAsides) -> Unit = {}) : SetAsideRepository {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SetAsides> = _state.asStateFlow()

    override fun exclude(id: Long, excluded: Boolean) {
        change("exclude id=$id on=$excluded") { it.copy(excluded = if (excluded) it.excluded + id else it.excluded - id) }
    }

    override fun toggleExcluded(id: Long): Boolean = synchronized(lock) {
        val now = id !in _state.value.excluded
        exclude(id, now)
        now
    }

    override fun link(outId: Long?, inId: Long?) {
        if (outId == null && inId == null) return
        change("link out=$outId in=$inId") { s ->
            // A message is in one transfer at most: drop any earlier link that shares a leg with this one.
            val without = s.transfers.filter { !(outId != null && it.outId == outId) && !(inId != null && it.inId == inId) }
            // A transfer is not also "left out": otherwise taking the transfer back would quietly leave the payment out.
            s.copy(transfers = without + TransferLink(outId, inId), excluded = s.excluded - setOfNotNull(outId, inId))
        }
    }

    override fun unlink(id: Long) {
        change("unlink id=$id") { s -> s.copy(transfers = s.transfers.filter { it.outId != id && it.inId != id }) }
    }

    override fun dismissSuggestion(key: String) {
        change("dismiss suggestion") { it.copy(dismissed = it.dismissed + key) }
    }

    override fun restoreSuggestion(key: String) {
        change("restore suggestion") { it.copy(dismissed = it.dismissed - key) }
    }

    private fun change(what: String, apply: (SetAsides) -> SetAsides) = synchronized(lock) {
        val before = _state.value
        val after = apply(before)
        _state.value = after
        persist(after)
        DiagLog.d("ledger") {
            "$what: left out ${before.excluded.size}->${after.excluded.size}, transfers ${before.transfers.size}->${after.transfers.size}, " +
                "dismissed ${before.dismissed.size}->${after.dismissed.size}, changed=${before != after}"
        }
    }
}
