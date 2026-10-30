package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.InboxChange
import com.zyke.message.domain.repository.InboxScan
import com.zyke.message.domain.repository.MonthStamp
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveInboxTest {
    private val zone = ZoneId.systemDefault()

    /** Collects what the use case emits until every month has been read; the stream itself never ends. */
    private suspend fun collectAll(useCase: ObserveMessagesUseCase, lastCount: Int): List<List<com.zyke.message.domain.model.ClassifiedMessage>> = coroutineScope {
        val out = CopyOnWriteArrayList<List<com.zyke.message.domain.model.ClassifiedMessage>>()
        val job = launch(Dispatchers.Default) { useCase().collect { out += it } }
        withTimeout(10_000) { while (out.lastOrNull()?.size != lastCount) delay(10) }
        job.cancel()
        out.toList()
    }

    /** An inbox that can be read a month at a time, counting how often each month's text was read. */
    private class Months(private val messages: List<SmsMessage>, private val zone: ZoneId) : SmsRepository {
        val reads = ArrayList<Long>()
        override val supportsWindows = true
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
        override fun changes(): Flow<InboxChange> = flowOf(InboxChange.CHANGED)

        override fun scan(zone: ZoneId, sinceMillis: Long): InboxScan {
            val byMonth = messages.filter { it.date >= sinceMillis }.groupBy { YearMonth.from(java.time.Instant.ofEpochMilli(it.date).atZone(zone)) }
            val months = byMonth.entries.sortedByDescending { it.key }.map { (month, list) ->
                MonthStamp(
                    month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    list.size, list.sumOf { it.id } * 31 + list.count { it.isRead }
                )
            }
            return InboxScan(months, if (sinceMillis == 0L) messages.map { it.id }.toLongArray() else LongArray(0))
        }

        override fun readWindow(fromMillis: Long, untilMillis: Long): List<SmsMessage> {
            reads += fromMillis
            return messages.filter { it.date in fromMillis until untilMillis }.sortedByDescending { it.date }
        }
    }

    private fun sms(id: Long, month: YearMonth, day: Int) = SmsMessage(
        id, "AD-HDFCBK",
        "Sent Rs.${id}00.00\nFrom HDFC Bank A/C *5765\nTo SHOP $id\nOn 01/01/26\nRef $id",
        month.atDay(day).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true
    )

    @Test
    fun newestMonthComesFirst_thenOlderOnesAreAddedBehindIt() = runBlocking {
        val now = YearMonth.from(LocalDate.now())
        val inbox = listOf(sms(1, now, 1), sms(2, now.minusMonths(1), 5), sms(3, now.minusMonths(2), 5), sms(4, now.minusMonths(2), 6))
        val source = Months(inbox, zone)
        val useCase = ObserveMessagesUseCase(source, MessageClassifier(), Dispatchers.Unconfined)

        val emissions = collectAll(useCase, 4)

        // Every emission is newest first and each one holds everything the one before it did.
        assertEquals(listOf(1L), emissions.first().map { it.id })
        assertEquals(listOf(1L, 2L, 4L, 3L), emissions.last().map { it.id })
        emissions.zipWithNext().forEach { (a, b) -> assertTrue(b.map { it.id }.containsAll(a.map { it.id })) }
        assertTrue(useCase.progress.value.isDone)
        assertEquals(3, useCase.progress.value.monthsLoaded)
        assertEquals(4, useCase.progress.value.messagesTotal)
        // Each month's text was read once, newest month first.
        assertEquals(source.reads.sortedDescending(), source.reads)
        assertEquals(3, source.reads.size)
    }

    @Test
    fun aSmallInboxStillWorksWhenTheRepositoryCannotReadByMonth() = runBlocking {
        val now = YearMonth.from(LocalDate.now())
        val inbox = listOf(sms(1, now, 1), sms(2, now.minusMonths(1), 5))
        val repository = object : SmsRepository {
            override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox)
        }
        val result = collectAll(ObserveMessagesUseCase(repository, MessageClassifier(), Dispatchers.Unconfined), 2).last()
        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun monthsBeyondTheLimitAreNotKept() = runBlocking {
        val now = YearMonth.from(LocalDate.now())
        val inbox = (0 until 40).map { sms(it + 1L, now.minusMonths(it.toLong()), 3) }
        val useCase = ObserveMessagesUseCase(Months(inbox, zone), MessageClassifier(), Dispatchers.Unconfined)
        val last = collectAll(useCase, 36).last()
        assertEquals(36, last.size)
        assertFalse(useCase.progress.value.isWorking)
    }

    /** A cache that keeps what it is given, with the extras the quick start needs. */
    private class MemoryCache : com.zyke.message.domain.repository.ClassificationCache {
        private val store = java.util.concurrent.ConcurrentHashMap<Long, com.zyke.message.domain.model.ClassifiedMessage>()
        private val notes = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun load() = store.toMap()
        override fun save(messages: List<com.zyke.message.domain.model.ClassifiedMessage>) { messages.forEach { store[it.id] = it } }
        override fun retainOnly(ids: Set<Long>) { store.keys.retainAll(ids) }
        override fun clear() { store.clear() }
        override fun meta(key: String) = notes[key]
        override fun putMeta(key: String, value: String) { notes[key] = value }
        override fun loadWindow(fromMillis: Long, untilMillis: Long) =
            store.values.filter { it.message.date in fromMillis until untilMillis }.sortedByDescending { it.message.date }
        override fun dateRange() = store.values.map { it.message.date }.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() }
    }

    @Test
    fun comingBackLater_readsOnlyTheRecentMonths_andTheRestComesFromWhatWasRemembered() = runBlocking {
        val now = YearMonth.from(LocalDate.now())
        val inbox = listOf(sms(1, now, 2), sms(2, now.minusMonths(1), 5), sms(3, now.minusMonths(4), 5), sms(4, now.minusMonths(6), 6))
        val cache = MemoryCache()

        // The first visit reads everything and remembers it.
        val first = Months(inbox, zone)
        collectAll(ObserveMessagesUseCase(first, MessageClassifier(), Dispatchers.Unconfined, classificationCache = cache), 4)
        assertEquals(4, first.reads.size)

        // A later visit (a new run of the app) has the same inbox, nothing new.
        val second = Months(inbox, zone)
        val emissions = collectAll(ObserveMessagesUseCase(second, MessageClassifier(), Dispatchers.Unconfined, classificationCache = cache), 4)

        // Only the last month or two were read from the inbox; the older ones came from the cache.
        assertTrue(second.reads.toString(), second.reads.size <= 2)
        assertEquals(listOf(1L, 2L, 3L, 4L), emissions.last().map { it.id })
    }

    @Test
    fun aBusyMonthWithNothingNew_doesNotLookLikeAMountainOfNewMessages() = runBlocking {
        val now = YearMonth.from(LocalDate.now())
        // Many messages this month, all of them already seen on the last visit.
        val inbox = (1L..350L).map { sms(it, now, 1) } + sms(400, now.minusMonths(2), 5)
        val cache = MemoryCache()
        collectAll(ObserveMessagesUseCase(Months(inbox, zone), MessageClassifier(), Dispatchers.Unconfined, classificationCache = cache), 351)

        val second = ObserveMessagesUseCase(Months(inbox, zone), MessageClassifier(), Dispatchers.Unconfined, classificationCache = cache)
        val seenPending = CopyOnWriteArrayList<Int>()
        val watcher = launch(Dispatchers.Default) { second.progress.collect { seenPending += it.pending } }
        collectAll(second, 351)
        watcher.cancel()

        // Nothing new arrived, so the background reading is never asked for on account of it.
        assertTrue(seenPending.toString(), seenPending.all { it == 0 })
    }
}
