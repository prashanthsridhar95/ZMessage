package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SenderImageRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionPictureTest {
    private val first = "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo Burmy Foods\nOn 26/10/26\nRef 1"
    private val second = "Sent Rs.100.00\nFrom HDFC Bank A/C *5765\nTo Burmy Foods\nOn 26/10/26\nRef 2"

    private class Images(keys: Set<String>) : SenderImageRepository {
        override val keysWithImage: StateFlow<Set<String>> = MutableStateFlow(keys)
    }

    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    private fun read(keys: Set<String>) = runBlocking {
        val inbox = listOf(SmsMessage(1, "AD-HDFCBK", first, 2000, true), SmsMessage(2, "AD-HDFCBK", second, 1000, true))
        ObserveMessagesUseCase(Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, senderImages = Images(keys))().first()
    }

    @Test
    fun aPictureForOnePayment_isOnlyOnThatPayment() {
        val messages = read(setOf(CategoryKey.messageKey(1)))
        assertEquals(CategoryKey.messageKey(1), messages.first { it.id == 1L }.merchant.imageKey)
        assertNull(messages.first { it.id == 2L }.merchant.imageKey)
    }

    @Test
    fun aPictureForTheReceiver_isOnEveryPayment_andOnesOwnPictureBeatsIt() {
        val receiver = CategoryKey.normalise("Burmy Foods")
        val messages = read(setOf(receiver, CategoryKey.messageKey(1)))
        assertEquals(CategoryKey.messageKey(1), messages.first { it.id == 1L }.merchant.imageKey)
        assertEquals(receiver, messages.first { it.id == 2L }.merchant.imageKey)
    }
}
