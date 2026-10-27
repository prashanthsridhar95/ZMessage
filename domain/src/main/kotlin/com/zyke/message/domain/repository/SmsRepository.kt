package com.zyke.message.domain.repository

import com.zyke.message.domain.model.SmsMessage
import kotlinx.coroutines.flow.Flow

interface SmsRepository {
    /** Emits the current inbox immediately and again whenever it changes. */
    fun observeInbox(): Flow<List<SmsMessage>>

    /** Reads the inbox again now, even if nothing announced a change, e.g. when the app is brought to the front. */
    fun refresh() = Unit
}
