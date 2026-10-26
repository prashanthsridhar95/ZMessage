package com.zyke.message.data.repository

import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class SmsRepositoryImpl(
    private val dataSource: SmsDataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : SmsRepository {

    override fun observeInbox(): Flow<List<SmsMessage>> =
        dataSource.inboxChanges()
            .conflate()
            .map { dataSource.readInbox() }
            .flowOn(dispatcher)
}
