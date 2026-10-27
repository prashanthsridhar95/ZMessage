package com.zyke.message.domain

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOverrideTest {
    private val body = "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo  Burmy Foods \nOn 26/10/26\nRef 1"
    private val other = "Sent Rs.100.00\nFrom HDFC Bank A/C *5765\nTo SOMEONE ELSE\nOn 26/10/26\nRef 2"

    private class Overrides(initial: Map<String, MessageCategory>) : CategoryOverrideRepository {
        private val state = MutableStateFlow(initial)
        override val overrides: StateFlow<Map<String, MessageCategory>> = state
        override fun set(key: String, category: MessageCategory?) {
            state.value = if (category == null) state.value - key else state.value + (key to category)
        }
        override fun clearAll() { state.value = emptyMap() }
    }

    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    @Test
    fun chosenCategory_isAppliedToEveryMessageWithThatReceiver_andOnlyThem() = runBlocking {
        val inbox = listOf(SmsMessage(1, "AD-HDFCBK", body, 2000, true), SmsMessage(2, "AD-HDFCBK", other, 1000, true))
        val key = CategoryKey.normalise("Burmy Foods")
        val useCase = ObserveMessagesUseCase(
            Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, Overrides(mapOf(key to MessageCategory.TRAVEL))
        )

        val messages = useCase().first()
        val chosen = messages.first { it.id == 1L }
        assertEquals(MessageCategory.TRAVEL, chosen.category)
        assertEquals(MessageCategory.TRAVEL, chosen.merchant.category)
        assertTrue(chosen.categoryOverridden)
        assertFalse(messages.first { it.id == 2L }.categoryOverridden)
    }

    @Test
    fun anotherCategory_replacesTheAutomaticOne() = runBlocking {
        val inbox = listOf(SmsMessage(1, "AD-HDFCBK", body, 1_700_000_000_000L, true))
        val key = CategoryKey.normalise("Burmy Foods")
        val messages = ObserveMessagesUseCase(
            Sms(inbox), MessageClassifier(), Dispatchers.Unconfined, Overrides(mapOf(key to MessageCategory.FUEL))
        )().first()

        assertEquals(MessageCategory.FUEL, messages.single().category)
    }
}
