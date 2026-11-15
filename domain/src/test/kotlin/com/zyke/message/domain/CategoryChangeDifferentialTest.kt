package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.demo.DemoData
import com.zyke.message.domain.ledger.CardBillDecisions
import com.zyke.message.domain.ledger.CardBillPayments
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.ledger.SamePaymentDecisions
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.CategoryOnlyMemo
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A category change is applied to the inbox that was already put together (heavy stages patched), not by running every stage
 * again. These tests prove the patched result is the very result the full run gives, for random sets of choices.
 */
class CategoryChangeDifferentialTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val sms: List<SmsMessage> = DemoData.build(now, zone).messages
    private val classifier = MessageClassifier()

    private class Overrides : CategoryOverrideRepository {
        private val state = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override val overrides: StateFlow<Map<String, MessageCategory>> = state
        override fun set(key: String, category: MessageCategory?) {
            state.value = if (category == null) state.value - key else state.value + (key to category)
        }
        override fun clearAll() { state.value = emptyMap() }
    }

    private class Inbox(private val list: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(list)
    }

    private class Fixed(map: Map<String, MessageCategory>) : CategoryOverrideRepository {
        override val overrides: StateFlow<Map<String, MessageCategory>> = MutableStateFlow(map)
        override fun set(key: String, category: MessageCategory?) = Unit
        override fun clearAll() = Unit
    }

    /** The full run: a new pipeline has nothing remembered, so every stage is worked out over all messages. */
    private fun full(choices: Map<String, MessageCategory>): List<ClassifiedMessage> = runBlocking {
        ObserveMessagesUseCase(Inbox(sms), classifier, Dispatchers.Unconfined, categoryOverrides = Fixed(choices))().first()
    }

    @Test
    fun patchedInbox_isTheFullInbox_forRandomSequencesOfChoices() = runBlocking {
        val overrides = Overrides()
        val scope = CoroutineScope(Dispatchers.Default)
        val live = ObserveMessagesUseCase(Inbox(sms), classifier, Dispatchers.Default, categoryOverrides = overrides, scope = scope)
        val latest = AtomicReference<List<ClassifiedMessage>?>(null)
        val job = scope.launch { live().collect { latest.set(it) } }
        waitFor { latest.get() != null }

        val keys = full(emptyMap()).flatMap { listOfNotNull(CategoryKey.of(it), CategoryKey.messageKey(it.id)) }.distinct()
        val categories = MessageCategory.assignable
        val random = Random(20261115)
        val chosen = HashMap<String, MessageCategory>()
        repeat(40) { step ->
            val key = keys[random.nextInt(keys.size)]
            // Removing a choice is as common as adding one; a few steps change two at once.
            val category = if (chosen.containsKey(key) && random.nextInt(4) == 0) null else categories[random.nextInt(categories.size)]
            if (category == null) chosen.remove(key) else chosen[key] = category
            overrides.set(key, category)
            val expected = full(chosen.toMap())
            // Nothing is emitted when the choice changes no message, so what was last emitted is already the answer then.
            waitFor { latest.get() == expected }
            assertEquals("step $step", expected, latest.get())
        }
        job.cancel()
        scope.cancel()
    }

    @Test
    fun memo_patchesOnlyTheMessagesWhoseCategoryChanged_andReusesTheRest() {
        val base = full(emptyMap())
        val key = CategoryKey.of(base.first { it.content is com.zyke.message.domain.model.MessageContent.Transaction })!!
        val changed = base.map { CategoryKey.apply(it, mapOf(key to MessageCategory.TRAVEL)) }
        val decisions = SamePaymentDecisions()
        val memo = CategoryOnlyMemo()
        val compute: (List<ClassifiedMessage>) -> List<ClassifiedMessage> = { SamePayment.apply(it, decisions, zone) }

        val first = memo.run(base, decisions, compute)
        assertEquals(1, memo.fullRuns)
        val second = memo.run(changed, decisions, compute)
        assertEquals("the second run patches", 1, memo.fullRuns)
        assertEquals(1, memo.patchedRuns)
        assertEquals(SamePayment.apply(changed, decisions, zone), second)
        // Messages not filed under that payee are the very instances of the first result.
        first.indices.forEach { i -> if (CategoryKey.of(first[i]) != key) assertSame(first[i], second[i]) }
        assertTrue(second.any { it.category == MessageCategory.TRAVEL })

        // Other settings for the stage: run in full.
        memo.run(changed, SamePaymentDecisions(no = setOf(1L)), compute)
        assertEquals(2, memo.fullRuns)
    }

    @Test
    fun memo_runsCardBillsInFull_onlyWhenTheChangeCouldMatterToTheBillsReading() {
        val base = full(emptyMap())
        val neutral = setOf(MessageCategory.OTHERS, MessageCategory.UPI_PAYMENT, MessageCategory.CREDIT_CARDS, MessageCategory.PAY_LATER)
        val debits = base.filter { val c = it.content; c is com.zyke.message.domain.model.MessageContent.Transaction && !c.isReceived && !c.isCard && it.category !in neutral }
        val paid = debits.first()
        val tx = paid.content as com.zyke.message.domain.model.MessageContent.Transaction
        // A card bill for exactly the amount of that debit: now whether the debit's payee is "neutral" can decide whether it paid the bill.
        val bill = base.first { it.content !is com.zyke.message.domain.model.MessageContent.CardBill && it.id != paid.id }
            .copy(content = com.zyke.message.domain.model.MessageContent.CardBill(tx.bankName, "1234", tx.amount))
        val input = base.map { if (it.id == bill.id) bill else it }
        val decisions = CardBillDecisions()
        val compute: (List<ClassifiedMessage>) -> List<ClassifiedMessage> = { CardBillPayments.apply(it, decisions, zone) }
        val memo = CategoryOnlyMemo(CardBillPayments::categorySensitivity)
        memo.run(input, decisions, compute)

        // Another payee, amount nowhere near the bill's, to Others: patched.
        val far = debits.first { val c = it.content as com.zyke.message.domain.model.MessageContent.Transaction; (c.amount.toBigDecimal() - tx.amount.toBigDecimal()).abs() > java.math.BigDecimal.TEN && CategoryKey.of(it) != CategoryKey.of(paid) }
        val farOthers = input.map { CategoryKey.apply(it, mapOf(CategoryKey.of(far)!! to MessageCategory.OTHERS)) }
        assertEquals(CardBillPayments.apply(farOthers, decisions, zone), memo.run(farOthers, decisions, compute))
        assertEquals(1, memo.patchedRuns)

        // The debit the bill is for, to Others: run in full, and the answer is the full answer.
        val paidOthers = input.map { CategoryKey.apply(it, mapOf(CategoryKey.of(paid)!! to MessageCategory.OTHERS)) }
        assertEquals(CardBillPayments.apply(paidOthers, decisions, zone), memo.run(paidOthers, decisions, compute))
        assertEquals(2, memo.fullRuns)
    }

    @Test
    fun anyRandomChoices_giveTheSameCardBillsAndSamePaymentsPatchedOrFull() {
        val base = full(emptyMap())
        val keys = base.mapNotNull { CategoryKey.of(it) }.distinct()
        val random = Random(7)
        val billMemo = CategoryOnlyMemo(CardBillPayments::categorySensitivity)
        val sameMemo = CategoryOnlyMemo()
        val bills = CardBillDecisions()
        val same = SamePaymentDecisions()
        billMemo.run(base, bills) { CardBillPayments.apply(it, bills, zone) }
        sameMemo.run(base, same) { SamePayment.apply(it, same, zone) }
        repeat(60) {
            val picks = (0 until 1 + random.nextInt(3)).associate { keys[random.nextInt(keys.size)] to MessageCategory.entries[random.nextInt(MessageCategory.entries.size)] }
            val input = base.map { CategoryKey.apply(it, picks) }
            assertEquals(CardBillPayments.apply(input, bills, zone), billMemo.run(input, bills) { CardBillPayments.apply(it, bills, zone) })
            assertEquals(SamePayment.apply(input, same, zone), sameMemo.run(input, same) { SamePayment.apply(it, same, zone) })
        }
        assertTrue("some runs were patched", sameMemo.patchedRuns > 0)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun aBigInbox_categoryChangeIsAPatch_notAnotherRunOfTheHeavyStages() {
        val inbox = CategoryChangeCostTest.inbox(20_000).map { classifier.classify(it) }
        val key = CategoryKey.normalise("Shop Number 7")
        val filed = inbox.map { CategoryKey.apply(it, mapOf(key to MessageCategory.TRAVEL)) }
        val bills = CardBillDecisions()
        val same = SamePaymentDecisions()
        val billMemo = CategoryOnlyMemo(CardBillPayments::categorySensitivity)
        val sameMemo = CategoryOnlyMemo()
        val billsFirst = billMemo.run(inbox, bills) { CardBillPayments.apply(it, bills, zone) }
        val sameFirst = sameMemo.run(inbox, same) { SamePayment.apply(it, same, zone) }

        val billsNext = billMemo.run(filed, bills) { CardBillPayments.apply(it, bills, zone) }
        val sameNext = sameMemo.run(filed, same) { SamePayment.apply(it, same, zone) }

        assertEquals("the heavy stages ran once each, over the whole inbox", listOf(1, 1), listOf(billMemo.fullRuns, sameMemo.fullRuns))
        assertEquals(listOf(1, 1), listOf(billMemo.patchedRuns, sameMemo.patchedRuns))
        assertEquals(CardBillPayments.apply(filed, bills, zone), billsNext)
        assertEquals(SamePayment.apply(filed, same, zone), sameNext)
        val touched = inbox.count { CategoryKey.of(it) == key }
        assertTrue(touched > 0)
        assertEquals("only the payee's payments are new instances", touched, sameNext.indices.count { sameNext[it] !== sameFirst[it] })
        assertEquals(touched, billsNext.indices.count { billsNext[it] !== billsFirst[it] })
        assertTrue(sameNext.filter { CategoryKey.of(it) == key }.all { it.category == MessageCategory.TRAVEL && it.categoryOverridden })
    }
}
