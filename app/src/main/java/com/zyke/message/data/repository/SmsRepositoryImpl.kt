package com.zyke.message.data.repository

import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
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

    private companion object {
        const val CHANGE_DEBOUNCE_MILLIS = 400L
    }
}
