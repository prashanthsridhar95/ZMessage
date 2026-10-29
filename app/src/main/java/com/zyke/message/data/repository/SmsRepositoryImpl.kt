package com.zyke.message.data.repository

import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.InboxChange
import com.zyke.message.domain.repository.InboxScan
import com.zyke.message.domain.repository.SmsRepository
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class SmsRepositoryImpl(
    private val dataSource: SmsDataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SmsRepository {

    /**
     * The first read is immediate; later change notifications are coalesced, because the SMS
     * provider can fire several in a burst (a multipart message, a sync) and each one would
     * otherwise re-read and re-parse the whole inbox.
     */
    @OptIn(FlowPreview::class)
    override fun observeInbox(): Flow<List<SmsMessage>> = flow {
        var first = true
        emitAll(
            dataSource.inboxChanges().debounce {
                if (first) {
                    first = false
                    0L
                } else {
                    CHANGE_DEBOUNCE_MILLIS
                }
            }
        )
    }
        .conflate()
        .map { dataSource.readInbox() }
        .flowOn(dispatcher)

    override fun refresh() = dataSource.requestRefresh()

    override fun refreshRecent() = dataSource.requestRecentRefresh()

    override val supportsWindows: Boolean = true

    /**
     * Provider bursts are coalesced like in [observeInbox]; a requested refresh goes through at once as a complete
     * re-read, which is how a message deleted in another app is noticed.
     */
    @OptIn(FlowPreview::class)
    override fun changes(): Flow<InboxChange> = flow {
        var first = true
        emitAll(
            dataSource.changeEvents().debounce { change ->
                if (change == InboxChange.FULL || first) {
                    first = false
                    0L
                } else {
                    CHANGE_DEBOUNCE_MILLIS
                }
            }
        )
    }.conflate().flowOn(dispatcher)

    override fun scan(zone: ZoneId, sinceMillis: Long): InboxScan = dataSource.scan(zone, sinceMillis)

    override fun readWindow(fromMillis: Long, untilMillis: Long): List<SmsMessage> = dataSource.readWindow(fromMillis, untilMillis)

    private companion object {
        const val CHANGE_DEBOUNCE_MILLIS = 400L
    }
}
