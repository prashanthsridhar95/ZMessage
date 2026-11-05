package com.zyke.message.data.demo

import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The inbox in Demo mode: a fixed list of made-up messages held in memory. It never touches the phone's message
 * provider, the chat-notification store or the SMS permission, so the real inbox is not read and the demo works even
 * when the app has not been allowed to read messages. Nothing can be written to it; there is no write path at all.
 */
class DemoSmsRepository(messages: List<SmsMessage>) : SmsRepository {
    private val inbox = MutableStateFlow(messages)

    override fun observeInbox(): Flow<List<SmsMessage>> = inbox.asStateFlow()
}
