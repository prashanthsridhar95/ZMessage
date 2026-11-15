package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prints how long one category change takes to come out of the pipeline (read it with --info); asserts only that the change arrived, never a time. */
class CategoryChangeCostTest {
    class Overrides : CategoryOverrideRepository {
        private val state = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override val overrides: StateFlow<Map<String, MessageCategory>> = state
        override fun set(key: String, category: MessageCategory?) {
            state.value = if (category == null) state.value - key else state.value + (key to category)
        }
        override fun clearAll() { state.value = emptyMap() }
    }

    class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    companion object {
        fun inbox(n: Int): List<SmsMessage> {
            val base = 1_700_000_000_000L
            return List(n) { i ->
                val payee = "Shop Number ${i % 600}"
                val body = if (i % 5 == 0) "Rs.${100 + i % 900}.00 spent on HDFC Bank Card x1234 at $payee on 26-10-26. Avl limit Rs 50000"
                else "Sent Rs.${100 + i % 900}.00\nFrom HDFC Bank A/C *5765\nTo $payee\nOn 26/10/26\nRef $i"
                SmsMessage(i + 1L, "AD-HDFCBK", body, base - i * 3_600_000L, true)
            }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun printCost() = runBlocking {
        for (n in listOf(2000, 6000)) {
            val o = Overrides()
            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
            val uc = ObserveMessagesUseCase(Sms(inbox(n)), MessageClassifier(), Dispatchers.Default, o, scope = scope)
            val seen = java.util.concurrent.LinkedBlockingQueue<List<com.zyke.message.domain.model.ClassifiedMessage>>()
            val t0 = System.nanoTime()
            val j = scope.launch { uc().collect { seen.put(it) } }
            val first = seen.take()
            println("BENCH n=$n initial ${(System.nanoTime() - t0) / 1_000_000} ms, ${first.size} msgs")
            repeat(4) { r ->
                val key = CategoryKey.normalise("Shop Number ${r + 1}")
                val t = System.nanoTime()
                o.set(key, MessageCategory.TRAVEL)
                var list = seen.take()
                while (list.none { it.categoryOverridden && CategoryKey.of(it) == key }) list = seen.take()
                assertTrue(list.any { it.categoryOverridden && it.category == MessageCategory.TRAVEL })
                println("BENCH n=$n change#$r ${(System.nanoTime() - t) / 1_000_000} ms")
            }
            j.cancel(); scope.cancel()
        }
    }

    @Test
    fun printStageCosts() {
        val cls = MessageClassifier()
        val sms = inbox(6000)
        fun <T> time(name: String, f: () -> T): T { val t = System.nanoTime(); val r = f(); println("BENCH stage $name ${(System.nanoTime() - t) / 1_000_000} ms"); return r }
        val classified = time("classify") { sms.map { cls.classify(it) } }
        val zone = java.time.ZoneId.systemDefault()
        repeat(2) {
            val o = time("categoryKey.apply") { classified.map { CategoryKey.apply(it, mapOf("x" to MessageCategory.TRAVEL), emptyMap()) } }
            time("customCategories") { com.zyke.message.domain.categories.CustomCategories.apply(o, com.zyke.message.domain.categories.CustomCategoryState()) }
            time("cardBillPayments") { com.zyke.message.domain.ledger.CardBillPayments.apply(o, com.zyke.message.domain.ledger.CardBillDecisions(), zone) }
            time("merges") { com.zyke.message.domain.merge.Merges.apply(o, emptyMap()) }
            time("samePayment") { com.zyke.message.domain.ledger.SamePayment.apply(o, com.zyke.message.domain.ledger.SamePaymentDecisions(), zone) }
            time("annotate") { com.zyke.message.domain.insights.CategorySuggester.annotate(o) }
        }
    }
}
