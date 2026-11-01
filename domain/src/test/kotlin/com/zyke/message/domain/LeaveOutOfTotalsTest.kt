package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.ledger.SetAsideStore
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.MergeRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Leave out of totals" must do what the switch says, every time: on, off, quickly, merged, transfer-linked, undone. */
class LeaveOutOfTotalsTest {
    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    private class Merges(initial: Map<Long, Long>) : MergeRepository {
        override val merges: StateFlow<Map<Long, Long>> = MutableStateFlow(initial)
        override fun merge(primaryId: Long, duplicateId: Long) = Unit
        override fun unmerge(duplicateId: Long) = Unit
    }

    private fun debit(id: Long, amount: String = "540.00", last4: String = "5765") =
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C *$last4\nTo SOMEONE ELSE\nOn 30/10/26\nRef $id", 1_793_000_000_000L + id, true)

    /** Runs the real pipeline live against [store] and gives the latest list after [block] changes it. */
    private fun live(inbox: List<SmsMessage>, store: SetAsideStore, merges: Map<Long, Long> = emptyMap(), block: (latest: () -> List<ClassifiedMessage>) -> Unit) = runBlocking {
        val useCase = ObserveMessagesUseCase(Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, setAsides = store, mergeRepository = Merges(merges))
        var latest: List<ClassifiedMessage> = emptyList()
        val job = launch(Dispatchers.Unconfined) { useCase().collect { latest = it } }
        try { block { latest } } finally { job.cancel() }
    }

    @Test
    fun onThenOffThenOn_theMessageFollowsEveryTime() {
        val store = SetAsideStore()
        live(listOf(debit(1)), store) { latest ->
            assertNull(latest().single().setAside)
            assertTrue(store.toggleExcluded(1)); assertEquals(SetAside.EXCLUDED, latest().single().setAside)
            assertFalse(store.toggleExcluded(1)); assertNull(latest().single().setAside)
            assertTrue(store.toggleExcluded(1)); assertEquals(SetAside.EXCLUDED, latest().single().setAside)
        }
    }

    @Test
    fun rapidToggling_isOneFlipPerTap_evenWhenTheScreenIsStale() {
        val store = SetAsideStore()
        // The old way: each tap says "the opposite of what I last drew" and the screen has not caught up for the second tap.
        val drawn = false
        store.exclude(1, !drawn)
        store.exclude(1, !drawn)
        assertTrue("two stale absolute taps collapse into one (the old bug)", 1L in store.state.value.excluded)
        // The fix: each tap flips what is stored.
        val fixed = SetAsideStore()
        repeat(2) { fixed.toggleExcluded(1) }
        assertFalse(1L in fixed.state.value.excluded)
        repeat(7) { fixed.toggleExcluded(1) }
        assertTrue(1L in fixed.state.value.excluded)
    }

    @Test
    fun concurrentChangesFromTwoThreads_neverLoseOne() {
        // Leave-out runs on the main thread, linking a transfer on the write queue; both must survive.
        repeat(20) {
            val store = SetAsideStore()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            val a = pool.submit { start.await(); for (i in 1L..300L) store.exclude(i, true) }
            val b = pool.submit { start.await(); for (i in 1000L..1300L) store.link(i, null) }
            start.countDown(); a.get(); b.get(); pool.shutdown()
            assertEquals(300, store.state.value.excluded.size)
            assertEquals(301, store.state.value.transfers.size)
        }
    }

    @Test
    fun whatIsSaved_alwaysEndsAsWhatIsShown() {
        val saved = ArrayList<SetAsides>()
        val store = SetAsideStore(persist = { synchronized(saved) { saved += it } })
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val jobs = (0 until 4).map { t -> pool.submit { start.await(); for (i in 0 until 200) store.toggleExcluded((t * 7 + i % 5).toLong()) } }
        start.countDown(); jobs.forEach { it.get() }; pool.shutdown()
        assertEquals(store.state.value, saved.last())
    }

    @Test
    fun aMergedGroup_isToggledThroughTheDisplayedMessage() {
        val store = SetAsideStore()
        // 2 is a duplicate folded into 1; the screen only ever shows (and toggles) 1.
        live(listOf(debit(1), debit(2)), store, merges = mapOf(2L to 1L)) { latest ->
            assertEquals(listOf(1L), latest().map { it.id })
            store.toggleExcluded(latest().single().id)
            assertEquals(SetAside.EXCLUDED, latest().single().setAside)
            store.toggleExcluded(latest().single().id)
            assertNull(latest().single().setAside)
        }
    }

    @Test
    fun takingBackATransfer_doesNotRevealAForgottenLeaveOut() {
        val store = SetAsideStore()
        store.exclude(1, true)
        // Auto-detection or the person then calls it a transfer between own accounts...
        store.link(1, null)
        assertEquals(SetAside.TRANSFER, store.state.value.kindOf(1))
        // ...and "Not a transfer" must leave it counted again (it used to snap back to "left out").
        store.unlink(1)
        assertNull(store.state.value.kindOf(1))
    }

    @Test
    fun linkingDropsOnlyLinksSharingALeg_andUndoRestoresTheLink() {
        val store = SetAsideStore()
        store.link(1, 2); store.link(3, 4)
        store.link(1, null)
        assertEquals(listOf(TransferLink(3, 4), TransferLink(1, null)), store.state.value.transfers)
        store.unlink(1)
        store.link(1, 2) // undo of "Not a transfer"
        assertEquals(SetAside.TRANSFER, store.state.value.kindOf(2))
    }

    @Test
    fun undoOfLeavingOut_countsItAgain() {
        val store = SetAsideStore()
        live(listOf(debit(1)), store) { latest ->
            val on = store.toggleExcluded(1)
            assertEquals(SetAside.EXCLUDED, latest().single().setAside)
            store.exclude(1, !on) // the Undo button
            assertNull(latest().single().setAside)
        }
    }

    @Test
    fun everyChangeWritesALogLine_withIdsAndCountsOnly() {
        val lines = ArrayList<String>()
        com.zyke.message.domain.diag.DiagLog.sink = { tag, text -> if (tag == "ledger") lines += text }
        try {
            val store = SetAsideStore()
            store.toggleExcluded(42)
            store.link(7, null)
            assertEquals(2, lines.size)
            assertTrue(lines[0].contains("id=42") && lines[0].contains("left out 0->1"))
        } finally { com.zyke.message.domain.diag.DiagLog.sink = null }
    }
}
