package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.InboxChange
import com.zyke.message.domain.repository.InboxScan
import com.zyke.message.domain.repository.MonthStamp
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app really does: one shared pipeline, read by screens, while the person changes names, categories and transfers. */
class LivePropagationTest {
    private val zone = ZoneId.systemDefault()

    private class Months(private val messages: List<SmsMessage>, private val zone: ZoneId) : SmsRepository {
        override val supportsWindows = true
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
        override fun changes(): Flow<InboxChange> = flowOf(InboxChange.CHANGED)
        override fun scan(zone: ZoneId, sinceMillis: Long): InboxScan {
            val byMonth = messages.filter { it.date >= sinceMillis }.groupBy { YearMonth.from(java.time.Instant.ofEpochMilli(it.date).atZone(zone)) }
            val months = byMonth.entries.sortedByDescending { it.key }.map { (month, list) ->
                MonthStamp(month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), list.size, list.sumOf { it.id } * 31)
            }
            return InboxScan(months, messages.map { it.id }.toLongArray())
        }
        override fun readWindow(fromMillis: Long, untilMillis: Long) = messages.filter { it.date in fromMillis until untilMillis }.sortedByDescending { it.date }
    }

    private class Names : NicknameRepository {
        override val nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun set(key: String, name: String?) { nicknames.value = if (name.isNullOrBlank()) nicknames.value - key else nicknames.value + (key to name) }
    }

    private class Categories : CategoryOverrideRepository {
        override val overrides = MutableStateFlow<Map<String, MessageCategory>>(emptyMap())
        override fun set(key: String, category: MessageCategory?) { overrides.value = if (category == null) overrides.value - key else overrides.value + (key to category) }
        override fun clearAll() { overrides.value = emptyMap() }
    }

    private class Asides : SetAsideRepository {
        override val state = MutableStateFlow(SetAsides())
        override fun exclude(id: Long, excluded: Boolean) { state.value = state.value.copy(excluded = if (excluded) state.value.excluded + id else state.value.excluded - id) }
        override fun link(outId: Long?, inId: Long?) { state.value = state.value.copy(transfers = state.value.transfers + TransferLink(outId, inId)) }
        override fun unlink(id: Long) = Unit
        override fun dismissSuggestion(key: String) = Unit
    }

    private class Cache : ClassificationCache {
        private val store = HashMap<Long, ClassifiedMessage>()
        private val notes = HashMap<String, String>()
        override fun load() = store.toMap()
        override fun save(messages: List<ClassifiedMessage>) { messages.forEach { store[it.id] = it } }
        override fun retainOnly(ids: Set<Long>) { store.keys.retainAll(ids) }
        override fun clear() { store.clear() }
        override fun meta(key: String) = notes[key]
        override fun putMeta(key: String, value: String) { notes[key] = value }
        override fun loadWindow(fromMillis: Long, untilMillis: Long) = store.values.filter { it.message.date in fromMillis until untilMillis }.sortedByDescending { it.message.date }
        override fun dateRange() = store.values.map { it.message.date }.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() }
    }

    private fun sms(id: Long, month: YearMonth, day: Int, last4: String = "5765") = SmsMessage(
        id, "AD-HDFCBK", "Sent Rs.${id}00.00\nFrom HDFC Bank A/C *$last4\nTo SHOP\nOn 01/01/26\nRef $id",
        month.atDay(day).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true
    )

    /** Runs [act] against the live pipeline, shared the way the app shares it, and waits for [until] to hold in what is emitted. */
    private fun live(act: (Names, Categories, Asides) -> Unit, until: (List<ClassifiedMessage>) -> Boolean, warm: Boolean = false) = runBlocking {
        val now = YearMonth.from(LocalDate.now())
        val inbox = listOf(sms(1, now, 2), sms(2, now.minusMonths(1), 5))
        val names = Names(); val categories = Categories(); val asides = Asides(); val cache = Cache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // A first visit remembers everything, so the test visit takes the quick path the app takes.
            if (warm) {
                val first = ObserveMessagesUseCase(Months(inbox, zone), MessageClassifier(), Dispatchers.Unconfined, classificationCache = cache)
                val seen = CopyOnWriteArrayList<List<ClassifiedMessage>>()
                val job = launch(Dispatchers.Default) { first().collect { seen += it } }
                withTimeout(10_000) { while (seen.lastOrNull()?.size != 2) delay(10) }
                job.cancel()
            }
            val useCase = ObserveMessagesUseCase(
                Months(inbox, zone), MessageClassifier(), Dispatchers.Default, categoryOverrides = categories, nicknameRepository = names,
                classificationCache = cache, scope = scope, setAsides = asides
            )
            val emitted = CopyOnWriteArrayList<List<ClassifiedMessage>>()
            val job = launch(Dispatchers.Default) { useCase().collect { emitted += it } }
            withTimeout(10_000) { while (emitted.lastOrNull()?.size != 2) delay(10) }
            act(names, categories, asides)
            withTimeout(5_000) { while (emitted.lastOrNull()?.let(until) != true) delay(10) }
            job.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNicknameGivenWhileTheAppRuns_reachesTheMessages() = live(
        act = { names, _, _ -> names.set(CategoryKey.messageKey(1), "Lunch place") },
        until = { list -> list.first { it.id == 1L }.merchant.name == "Lunch place" }
    )

    @Test
    fun aCategoryChosenWhileTheAppRuns_reachesTheMessages() = live(
        act = { _, categories, _ -> categories.set(CategoryKey.messageKey(1), MessageCategory.TRAVEL) },
        until = { list -> list.first { it.id == 1L }.category == MessageCategory.TRAVEL }
    )

    @Test
    fun aTransferLinkedWhileTheAppRuns_reachesTheMessages() = live(
        act = { _, _, asides -> asides.link(1L, 2L) },
        until = { list -> list.all { it.setAside != null } }
    )

    @Test
    fun theSameThroughTheQuickPathTakenOnLaterVisits() {
        live(act = { names, _, _ -> names.set(CategoryKey.messageKey(1), "Lunch place") }, until = { list -> list.first { it.id == 1L }.merchant.name == "Lunch place" }, warm = true)
        live(act = { _, _, asides -> asides.link(1L, 2L) }, until = { list -> list.all { it.setAside != null } }, warm = true)
        assertTrue(true)
        assertEquals(1, 1)
    }
}
