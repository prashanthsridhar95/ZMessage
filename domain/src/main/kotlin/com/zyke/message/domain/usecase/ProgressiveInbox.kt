package com.zyke.message.domain.usecase

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.InboxChange
import com.zyke.message.domain.repository.MonthStamp
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transformLatest

/**
 * Reads a very large inbox a month at a time, newest first. The current month appears as soon as it is read, then
 * September, August and so on are added behind it while the person is already using the app. Months that have not
 * changed since they were read are skipped, so a new message costs one month, not the whole inbox.
 *
 * Only [maxMonths] months are kept in memory; with a million messages that is what keeps the phone responsive.
 */
class ProgressiveInbox(
    private val repository: SmsRepository,
    /** Turns one month of messages into classified ones (remembering, reading and saving them). */
    private val classify: suspend (List<SmsMessage>) -> List<ClassifiedMessage>,
    private val cache: ClassificationCache,
    private val userState: UserMessageStateRepository?,
    private val maxMonths: Int = DEFAULT_MAX_MONTHS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Window(val stamp: Long, val messages: List<ClassifiedMessage>)

    private val lock = Any()
    private val windows = HashMap<Long, Window>()
    private var knownMonths: List<MonthStamp> = emptyList()
    private var everEmitted = false
    private var firstSync = false
    private var pendingNew = 0

    /**
     * A pass that is replaced by a newer one (a message arrives while months are still being read) stops where it is.
     * These remember that, so the next pass carries on with the same job instead of treating the half-read inbox as
     * read, and so the "read in full" mark is only left when the whole inbox really was read.
     */
    private var fullIncomplete = false
    private var quickIncomplete = false

    private val _progress = MutableStateFlow(LoadProgress())
    val progress: StateFlow<LoadProgress> = _progress

    fun messages(): Flow<List<ClassifiedMessage>> = repository.changes().transformLatest { change -> pass(change) }

    private suspend fun FlowCollector<List<ClassifiedMessage>>.pass(change: InboxChange) {
        val zone = ZoneId.systemDefault()
        val now = clock()
        val cold = synchronized(lock) { windows.isEmpty() } || quickIncomplete
        val syncedThrough = cache.meta(SYNCED_THROUGH)?.toLongOrNull()
        val lastFull = cache.meta(FULL_SYNC_AT)?.toLongOrNull()
        val cached = cache.dateRange()

        // Coming back to the app: what was read before is remembered, so only what arrived since is read now, and the
        // inbox is checked in full only now and then (that is also how messages deleted elsewhere are noticed).
        if (cold && change != InboxChange.FULL && syncedThrough != null && lastFull != null && cached != null && now - lastFull < VERIFY_EVERY_MS) {
            incrementalPass(zone, now, syncedThrough, cached)
            return
        }

        val full = change == InboxChange.FULL || cold || fullIncomplete
        if (full) fullIncomplete = true
        firstSync = lastFull == null && full
        val scan = repository.scan(zone, if (full) 0L else recentFrom(zone)) ?: return
        val months = scan.months.take(maxMonths)

        if (full) {
            synchronized(lock) {
                windows.keys.retainAll(months.mapTo(HashSet()) { it.start })
                knownMonths = months
            }
        }
        publishProgress()

        var changed = false
        var lastEmit = 0L
        months.forEach { month ->
            currentCoroutineContext().ensureActive()
            val have = synchronized(lock) { windows[month.start] }
            if (have != null && have.stamp == month.stamp) return@forEach

            val messages = classify(repository.readWindow(month.start, month.endExclusive))
            synchronized(lock) {
                windows[month.start] = Window(month.stamp, messages)
                if (!full && knownMonths.none { it.start == month.start }) knownMonths = (knownMonths + month).sortedByDescending { it.start }
            }
            changed = true
            publishProgress()

            // The first month goes out at once; the rest at a steady pace, so screens are not rebuilt for every month.
            val at = clock()
            if (!everEmitted || at - lastEmit >= EMIT_EVERY_MS) {
                emit(assemble())
                everEmitted = true
                lastEmit = at
            }
        }

        if (changed || !everEmitted) {
            emit(assemble())
            everEmitted = true
        }
        firstSync = false
        if (full) fullIncomplete = false
        publishProgress()

        // The whole inbox has been read: remember how far, so the next visit can start from here.
        remember(now, full = full)

        // With the whole inbox seen, what has gone from it (deleted in the messaging app) is forgotten here too.
        if (full && scan.allIds.isNotEmpty()) {
            val present = scan.allIds.toHashSet()
            cache.retainOnly(present)
            userState?.retainOnly(present)
        }
    }

    /**
     * The quick way in: the messages of the last month or so are read from the phone's inbox, and every older month
     * comes straight from what was remembered, without touching the inbox or the rules. Newest first, as always.
     */
    private suspend fun FlowCollector<List<ClassifiedMessage>>.incrementalPass(zone: ZoneId, now: Long, syncedThrough: Long, cached: Pair<Long, Long>) {
        quickIncomplete = true
        val boundary = monthStart(minOf(syncedThrough, now) - DAY_MS, zone)
        val recent = repository.scan(zone, boundary)?.months.orEmpty()
        // Only what arrived since the last visit is new; the month it falls in is read again, but mostly from memory.
        pendingNew = repository.readWindow(syncedThrough + 1, Long.MAX_VALUE).size

        // Every month there is, from the newest to the oldest remembered one, up to the limit.
        val newestMonth = YearMonth.from(Instant.ofEpochMilli(maxOf(cached.second, recent.firstOrNull()?.start ?: 0L)).atZone(zone))
        val oldestMonth = YearMonth.from(Instant.ofEpochMilli(cached.first).atZone(zone))
        val all = generateSequence(newestMonth) { it.minusMonths(1) }.takeWhile { !it.isBefore(oldestMonth) }.take(maxMonths).map { month ->
            val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            MonthStamp(start, month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), 0, -1L)
        }.toList()
        synchronized(lock) { knownMonths = all }
        publishProgress()

        var lastEmit = 0L
        suspend fun add(month: MonthStamp, messages: List<ClassifiedMessage>, stamp: Long) {
            synchronized(lock) {
                windows[month.start] = Window(stamp, messages)
                knownMonths = knownMonths.map { if (it.start == month.start) it.copy(count = messages.size) else it }
            }
            publishProgress()
            val at = clock()
            if (!everEmitted || at - lastEmit >= EMIT_EVERY_MS) {
                emit(assemble())
                everEmitted = true
                lastEmit = at
            }
        }

        // What is new, first: it is small, and it is what the person came to see.
        recent.forEach { month ->
            currentCoroutineContext().ensureActive()
            add(month, classify(repository.readWindow(month.start, month.endExclusive)), month.stamp)
        }
        pendingNew = 0
        all.filter { it.start < boundary }.forEach { month ->
            currentCoroutineContext().ensureActive()
            val remembered = cache.loadWindow(month.start, month.endExclusive)
            if (remembered.isNotEmpty()) add(month, remembered, -1L)
        }
        // Months with nothing remembered are not months to wait for.
        synchronized(lock) { knownMonths = knownMonths.filter { it.start in windows } }
        emit(assemble())
        everEmitted = true
        publishProgress()
        quickIncomplete = false
        remember(now, full = false)
    }

    /** Notes how far the inbox has been read, so the next visit starts from there. */
    private fun remember(now: Long, full: Boolean) {
        val newest = synchronized(lock) { windows.values.flatMap { w -> w.messages.map { it.message.date } }.maxOrNull() } ?: return
        cache.putMeta(SYNCED_THROUGH, newest.toString())
        if (full) cache.putMeta(FULL_SYNC_AT, now.toString())
    }

    private fun monthStart(millis: Long, zone: ZoneId): Long =
        YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone)).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun assemble(): List<ClassifiedMessage> = synchronized(lock) {
        windows.entries.sortedByDescending { it.key }.flatMap { it.value.messages }
    }

    private fun publishProgress() {
        synchronized(lock) {
            val loaded = knownMonths.filter { it.start in windows }
            _progress.value = LoadProgress(
                monthsLoaded = loaded.size,
                monthsTotal = knownMonths.size,
                messagesLoaded = loaded.sumOf { it.count },
                messagesTotal = knownMonths.sumOf { it.count },
                oldestLoaded = loaded.minOfOrNull { it.start },
                isDone = loaded.size >= knownMonths.size,
                isFirstSync = firstSync,
                pending = pendingNew
            )
        }
    }

    /** A new message can only land in the current month (or the one before, around midnight), so only those are looked at. */
    private fun recentFrom(zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        return today.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    companion object {
        const val DEFAULT_MAX_MONTHS = 36
        private const val EMIT_EVERY_MS = 1_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** The inbox is checked in full this often, which is also how a message deleted elsewhere is noticed. */
        private const val VERIFY_EVERY_MS = 7 * DAY_MS
        private const val SYNCED_THROUGH = "synced_through"
        private const val FULL_SYNC_AT = "full_sync_at"
    }
}
