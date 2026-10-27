package com.zyke.message.domain.usecase

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.InboxChange
import com.zyke.message.domain.repository.MonthStamp
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import java.time.Instant
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

    private val _progress = MutableStateFlow(LoadProgress())
    val progress: StateFlow<LoadProgress> = _progress

    fun messages(): Flow<List<ClassifiedMessage>> = repository.changes().transformLatest { change -> pass(change) }

    private suspend fun FlowCollector<List<ClassifiedMessage>>.pass(change: InboxChange) {
        val zone = ZoneId.systemDefault()
        val full = change == InboxChange.FULL || synchronized(lock) { windows.isEmpty() }
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
            val now = clock()
            if (!everEmitted || now - lastEmit >= EMIT_EVERY_MS) {
                emit(assemble())
                everEmitted = true
                lastEmit = now
            }
        }

        if (changed || !everEmitted) {
            emit(assemble())
            everEmitted = true
        }
        publishProgress()

        // With the whole inbox seen, what has gone from it (deleted in the messaging app) is forgotten here too.
        if (full && scan.allIds.isNotEmpty()) {
            val present = scan.allIds.toHashSet()
            cache.retainOnly(present)
            userState?.retainOnly(present)
        }
    }

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
                isDone = loaded.size >= knownMonths.size
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
    }
}
