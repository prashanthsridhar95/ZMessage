package com.zyke.message.domain.repository

import com.zyke.message.domain.model.SmsMessage
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Why the inbox is being looked at again. */
enum class InboxChange {
    /** The provider announced something: usually a new message. */
    CHANGED,

    /** The person (or the app starting) asked for a complete re-read, e.g. to see deletions in the messaging app. */
    FULL
}

/** One calendar month of the inbox, described without reading any message text. */
data class MonthStamp(
    /** Start of the month in epoch millis, inclusive. */
    val start: Long,
    /** Start of the next month, exclusive. */
    val endExclusive: Long,
    val count: Int,
    /** Changes whenever a message in the month is added, removed or marked read, so unchanged months can be skipped. */
    val stamp: Long
)

/** What [SmsRepository.scan] found: the months, newest first, and every message id when the scan covered the whole inbox. */
class InboxScan(val months: List<MonthStamp>, val allIds: LongArray)

interface SmsRepository {
    /** Emits the current inbox immediately and again whenever it changes. */
    fun observeInbox(): Flow<List<SmsMessage>>

    /** Reads the inbox again now, even if nothing announced a change, e.g. when the app is brought to the front. */
    fun refresh() = Unit

    /**
     * True when the inbox can be read a month at a time through [scan] and [readWindow], which is what lets a very
     * large inbox appear newest month first instead of after everything has been read.
     */
    val supportsWindows: Boolean get() = false

    /** Emits once on collection, then whenever the inbox may have changed. */
    fun changes(): Flow<InboxChange> = emptyFlow()

    /**
     * Lists the months from [sinceMillis] onwards (all of them when 0), newest first, reading only ids, dates and
     * read flags, never the text. [InboxScan.allIds] is filled in only for a scan from the beginning.
     */
    fun scan(zone: ZoneId, sinceMillis: Long = 0L): InboxScan? = null

    /** The messages dated from [fromMillis] up to, but not including, [untilMillis], newest first. */
    fun readWindow(fromMillis: Long, untilMillis: Long): List<SmsMessage> = emptyList()
}
