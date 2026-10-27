package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveMessagesUseCaseTest {
    private class FakeRepository(private val inbox: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox)
    }

    private fun sms(id: Long, body: String, date: Long) = SmsMessage(id, "S", body, date, true)

    @Test
    fun sortsNewestFirstAndDropsSameDayDuplicates() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val base = 1_700_000_000_000L
        val useCase = ObserveMessagesUseCase(
            FakeRepository(
                listOf(
                    sms(1, "a", base),
                    sms(2, "b", base + 1000),
                    sms(3, "a", base + 2000),
                    sms(4, "a", base - 3 * day)
                )
            ),
            MessageClassifier(),
            Dispatchers.Unconfined
        )

        val ids = useCase().first().map { it.id }
        assertEquals(listOf(3L, 2L, 4L), ids)
    }
}
