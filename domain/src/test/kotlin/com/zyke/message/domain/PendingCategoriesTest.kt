package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.PendingCategories
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.feed.Feed
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.FeedPatch
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCategoriesTest {
    private val classifier = MessageClassifier()
    private val base = 1_700_000_000_000L

    private fun debit(id: Long, payee: String) = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.${100 + id}.00\nFrom HDFC Bank A/C *5765\nTo $payee\nOn 26/10/26\nRef $id", base - id * 3_600_000L, true)
    )

    private val inbox = listOf(debit(1, "Burmy Foods"), debit(2, "Burmy Foods"), debit(3, "Other Shop"))
    private val burmy = CategoryKey.of(inbox[0])!!

    @Test
    fun aPayeeChoice_reachesEveryPaymentToThatPayee_andOnlyThem() {
        val pending = PendingCategories()
        pending.add(burmy, MessageCategory.TRAVEL)
        val shown = pending.overlay(inbox)
        assertEquals(listOf(MessageCategory.TRAVEL, MessageCategory.TRAVEL), shown.take(2).map { it.category })
        assertTrue(shown.take(2).all { it.categoryOverridden && !it.categoryOwn && it.merchant.category == MessageCategory.TRAVEL })
        assertSame("a payment to somebody else is the same instance", inbox[2], shown[2])
    }

    @Test
    fun overlay_isWhatThePipelineStageMakes() {
        val pending = PendingCategories()
        val choices = mapOf(burmy to MessageCategory.FUEL, CategoryKey.messageKey(2) to MessageCategory.HEALTH)
        choices.forEach { (k, v) -> pending.add(k, v) }
        assertEquals(inbox.map { CategoryKey.apply(it, choices) }, pending.overlay(inbox))
    }

    @Test
    fun aChoiceForOnePayment_beatsOneForItsPayee_inEitherOrder() {
        for (payeeFirst in listOf(true, false)) {
            val pending = PendingCategories()
            val one = { pending.add(CategoryKey.messageKey(2), MessageCategory.HEALTH) }
            val all = { pending.add(burmy, MessageCategory.FUEL) }
            if (payeeFirst) { all(); one() } else { one(); all() }
            val shown = pending.overlay(inbox)
            assertEquals(MessageCategory.FUEL, shown[0].category)
            assertEquals(MessageCategory.HEALTH, shown[1].category)
            assertTrue(shown[1].categoryOwn)
        }
    }

    @Test
    fun aPayeeChoice_doesNotTakeOverAPaymentThatHasItsOwn() {
        val own = CategoryKey.apply(inbox[1], mapOf(CategoryKey.messageKey(2) to MessageCategory.HEALTH))
        val pending = PendingCategories().apply { add(burmy, MessageCategory.FUEL) }
        assertSame(own, pending.overlay(listOf(own))[0])
    }

    @Test
    fun nothingPending_isTheSameList_andTakingAChoiceAwayIsNotShownAhead() {
        val pending = PendingCategories()
        assertSame(inbox, pending.overlay(inbox))
        pending.add(burmy, MessageCategory.FUEL)
        pending.add(burmy, null)
        assertSame(inbox, pending.overlay(inbox))
    }

    @Test
    fun settle_forgetsTheChoice_whenTheInboxThatCameBackCarriesIt() {
        val pending = PendingCategories()
        pending.add(burmy, MessageCategory.TRAVEL)
        val older = pending.settle(inbox)
        assertEquals("an inbox from before the tap does not take the choice away", MessageCategory.TRAVEL, older[0].category)
        assertFalse(pending.isEmpty())

        val confirmed = inbox.map { CategoryKey.apply(it, mapOf(burmy to MessageCategory.TRAVEL)) }
        assertSame("what came back is shown as it is: no flicker", confirmed, pending.settle(confirmed))
        assertTrue(pending.isEmpty())
        // A later change made elsewhere is not undone by a choice that was already confirmed.
        val later = confirmed.map { CategoryKey.apply(it, mapOf(burmy to MessageCategory.FOOD)) }
        assertSame(later, pending.settle(later))
    }

    @Test
    fun aChoiceTheInboxNeverConfirms_isForgottenAfterAWhile() {
        var now = 0L
        val pending = PendingCategories({ now }, expiryNanos = 1_000L)
        pending.add(burmy, MessageCategory.TRAVEL)
        assertEquals(MessageCategory.TRAVEL, pending.overlay(inbox)[0].category)
        now = 2_000L
        assertSame(inbox, pending.overlay(inbox))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun feedPatch_showsTheNewCategoryOnTheRows_andKeepsTheOthersAsTheyWere() {
        val zone = ZoneId.of("Asia/Kolkata")
        val at = Instant.ofEpochMilli(base)
        val feed = FeedBuilder.build(inbox, com.zyke.message.domain.model.UserMessageState(), MessageFilter.All, "", at, zone)
        val shown = PendingCategories().apply { add(burmy, MessageCategory.TRAVEL) }.overlay(inbox)
        val patched = FeedPatch.sync(feed, FeedPatch.changed(inbox, shown))
        fun rows(f: Feed) = f.sections.flatMap { it.items }.filterIsInstance<FeedItem.Single>()
        assertEquals(setOf(MessageCategory.TRAVEL), rows(patched).filter { it.message.id in setOf(1L, 2L) }.map { it.message.category }.toSet())
        val untouched = rows(feed).first { it.message.id == 3L }
        assertSame(untouched, rows(patched).first { it.message.id == 3L })
        assertNotSame(feed, patched)
        assertSame("nothing newer: the same feed", feed, FeedPatch.sync(feed, emptyMap()))
    }

    private class Inbox(private val list: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(list)
    }

    @Test
    fun theUseCaseSaysHowLongAChangeTook_withCountsOnly() {
        val lines = CopyOnWriteArrayList<String>()
        val before = DiagLog.sink
        DiagLog.sink = { tag, message -> if (tag == "category") lines += message }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val overrides = CategoryChangeCostTest.Overrides()
            val useCase = ObserveMessagesUseCase(Inbox(inbox.map { it.message }), classifier, Dispatchers.Default, overrides, scope = scope)
            val latest = java.util.concurrent.atomic.AtomicReference<List<ClassifiedMessage>?>(null)
            scope.launch { useCase().collect { latest.set(it) } }
            waitFor { latest.get() != null }
            overrides.set(burmy, MessageCategory.TRAVEL)
            waitFor { lines.any { it.startsWith("change applied in ") } }
            val line = lines.first { it.startsWith("change applied in ") }
            assertTrue(line, Regex("""change applied in \d+ ms \(3 messages\)""").matches(line))
            assertFalse("no payee name in the log", lines.any { it.contains("Burmy", ignoreCase = true) })
        } finally {
            DiagLog.sink = before
            scope.cancel()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }
}
