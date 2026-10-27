package com.zyke.message.data.sms

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.InboxChange
import com.zyke.message.domain.repository.InboxScan
import com.zyke.message.domain.repository.MonthStamp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Reads the device SMS inbox through the telephony content provider. */
class SmsDataSource(
    private val contentResolver: ContentResolver,
    private val rcsStore: RcsMessageStore? = null
) {

    fun readInbox(): List<SmsMessage> {
        val sms = readSms()
        return rcsStore?.mergeWith(sms) ?: sms
    }

    /**
     * Lists the months from [sinceMillis] onwards, newest first, from the id, date and read flag of each message only:
     * no text is read, so even a very large inbox is described in a moment. Messages already in the phone's order
     * (newest first) are counted into the month they belong to without building an object for each.
     */
    fun scan(zone: ZoneId, sinceMillis: Long): InboxScan {
        val ids = LongArray(INITIAL_IDS)
        var idCount = 0
        var growing = ids
        val wantIds = sinceMillis == 0L

        class Bucket(val start: Long, val end: Long) {
            var count = 0
            var idSum = 0L
            var readSum = 0L
        }
        val buckets = ArrayList<Bucket>()
        var current: Bucket? = null

        fun bucketFor(date: Long): Bucket {
            current?.takeIf { date >= it.start && date < it.end }?.let { return it }
            val month = YearMonth.from(Instant.ofEpochMilli(date).atZone(zone))
            val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return (buckets.firstOrNull { it.start == start } ?: Bucket(start, end).also { buckets += it }).also { current = it }
        }

        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.READ),
            if (sinceMillis > 0) "${Telephony.Sms.DATE} >= ?" else null,
            if (sinceMillis > 0) arrayOf(sinceMillis.toString()) else null,
            "${Telephony.Sms.DATE} DESC"
        )?.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readColumn = it.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val bucket = bucketFor(it.getLong(dateColumn))
                bucket.count++
                bucket.idSum += id
                bucket.readSum += it.getInt(readColumn)
                if (wantIds) {
                    if (idCount == growing.size) growing = growing.copyOf(growing.size * 2)
                    growing[idCount++] = id
                }
            }
        }

        // RCS messages are described the same way, so they are part of every month and of the list of ids.
        val rcs = rcsStore?.all().orEmpty().filter { it.date >= sinceMillis }
        val allIds = if (wantIds) growing.copyOf(idCount) + rcs.map { it.id }.toLongArray() else LongArray(0)
        rcs.forEach { message ->
            val bucket = bucketFor(message.date)
            bucket.count++
            bucket.idSum += message.id
            bucket.readSum += if (message.isRead) 1 else 0
        }

        val months = buckets.sortedByDescending { it.start }
            .map { MonthStamp(it.start, it.end, it.count, it.count * STAMP_COUNT + it.idSum * STAMP_IDS + it.readSum) }
        return InboxScan(months, allIds)
    }

    /** The messages of one month, newest first, with that month's RCS messages added. */
    fun readWindow(fromMillis: Long, untilMillis: Long): List<SmsMessage> {
        val sms = readSms("${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?", arrayOf(fromMillis.toString(), untilMillis.toString()))
        return rcsStore?.mergeWindow(sms, fromMillis, untilMillis) ?: sms
    }

    private fun readSms(selection: String? = null, args: Array<String>? = null): List<SmsMessage> {
        val cursor = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            PROJECTION,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC"
        ) ?: return emptyList()

        return cursor.use {
            val id = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val address = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val read = it.getColumnIndexOrThrow(Telephony.Sms.READ)

            buildList {
                while (it.moveToNext()) {
                    add(
                        SmsMessage(
                            id = it.getLong(id),
                            sender = it.getString(address).orEmpty(),
                            body = it.getString(body).orEmpty(),
                            date = it.getLong(date),
                            isRead = it.getInt(read) == 1
                        )
                    )
                }
            }
        }
    }

    /** Emits once on collection, then on every change to the SMS provider. */
    fun inboxChanges(): Flow<Unit> =
        if (rcsStore == null) merge(providerChanges(), refreshes) else merge(providerChanges(), rcsStore.changes, refreshes)

    /** What the repository listens to: provider bursts become [InboxChange.CHANGED] and a requested refresh becomes [InboxChange.FULL]. */
    fun changeEvents(): Flow<InboxChange> =
        merge(inboxChanges().map { InboxChange.CHANGED }, fullRefreshes.map { InboxChange.FULL })

    private val fullRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Asks every collector to read the inbox again, whether or not the provider announced a change. */
    fun requestRefresh() {
        refreshes.tryEmit(Unit)
        fullRefreshes.tryEmit(Unit)
    }

    private fun providerChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }

    private companion object {
        const val INITIAL_IDS = 4096
        const val STAMP_COUNT = 1_000_003L
        const val STAMP_IDS = 31L
        val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )
    }
}
