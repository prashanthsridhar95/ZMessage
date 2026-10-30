package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.NicknameRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknameTest {
    private val body = "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo  Burmy Foods \nOn 26/10/26\nRef 1"
    private val other = "Sent Rs.100.00\nFrom HDFC Bank A/C *5765\nTo SOMEONE ELSE\nOn 26/10/26\nRef 2"

    private class Names(initial: Map<String, String>) : NicknameRepository {
        override val nicknames: StateFlow<Map<String, String>> = MutableStateFlow(initial)
        override fun set(key: String, name: String?) = Unit
    }

    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    @Test
    fun nickname_replacesTheNameForEveryMessageWithThatReceiver() = runBlocking {
        val inbox = listOf(SmsMessage(1, "AD-HDFCBK", body, 2000, true), SmsMessage(2, "AD-HDFCBK", other, 1000, true))
        val names = Names(mapOf(CategoryKey.normalise("Burmy Foods") to "Lunch place"))
        val messages = ObserveMessagesUseCase(
            Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, nicknameRepository = names
        )().first()

        val renamed = messages.first { it.id == 1L }
        assertEquals("Lunch place", renamed.merchant.name)
        assertTrue(renamed.merchant.renamed)
        assertFalse(messages.first { it.id == 2L }.merchant.renamed)
    }

    @Test
    fun aNameGivenToAPhoneNumber_showsOnItsConversation() = runBlocking {
        val number = "+919876543210"
        val inbox = listOf(SmsMessage(1, number, "See you at 7 then", 2000, true))
        val names = Names(mapOf(CategoryKey.normalise(number) to "Ravi"))
        val messages = ObserveMessagesUseCase(Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, nicknameRepository = names)().first()

        val conversation = com.zyke.message.domain.conversation.ConversationBuilder()
            .conversations(messages, com.zyke.message.domain.model.UserMessageState()).single()
        assertEquals("Ravi", conversation.merchant.name)
        assertTrue(conversation.merchant.renamed)
    }
}
