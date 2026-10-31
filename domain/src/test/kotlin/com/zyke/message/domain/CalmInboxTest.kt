package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CalmInboxTest {
    private class Sms(val inbox: MutableStateFlow<List<SmsMessage>>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = inbox
    }

    @Test
    fun calm_showsFirstListAtOnce_andTheLatestAfterABurst() = runBlocking {
        val inbox = MutableStateFlow(listOf(SmsMessage(1, "A", "hello", 1000, true)))
        val calm = ObserveMessagesUseCase(Sms(inbox), MessageClassifier(), Dispatchers.Unconfined).calm()
        assertEquals(1, calm.first().size)
        // A finished inbox is passed on without waiting; the last value is always delivered.
        inbox.value = listOf(SmsMessage(1, "A", "hello", 1000, true), SmsMessage(2, "A", "hi there", 2000, true))
        assertEquals(2, calm.first().size)
        assertEquals(2, calm.take(1).toList().single().size)
    }
}
