package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationCacheTest {
    private val classifier = MessageClassifier()

    private class MemoryCache : ClassificationCache {
        val entries = HashMap<Long, ClassifiedMessage>()
        var saved = 0
        override fun load() = HashMap(entries)
        override fun save(messages: List<ClassifiedMessage>) {
            saved += messages.size
            messages.forEach { entries[it.id] = it }
        }
        override fun retainOnly(ids: Set<Long>) { entries.keys.retainAll(ids) }
        override fun clear() = entries.clear()
    }

    private class Sms(var messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    private val bodies = listOf(
        "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1",
        "123456 is your OTP for login. Do not share.",
        "Your Amazon order #402-1234567-1234567 has been shipped.",
        "INR 65 toll paid from HDFC Bank Tag 1234 for vehicle no. KA01AB1234 at Hosur Toll on 12-Jan-25 10:30. Avbl. Bal.: INR435",
        "Your car insurance policy expires on 15-Dec-2026.",
        "Autopay Failed:\nINR.649.00 on 10/10/2026 via HDFC Bank Card 1234 for Netflix",
        "Hello there"
    )

    private fun inbox() = bodies.mapIndexed { i, body -> SmsMessage(i + 1L, "AD-HDFCBK", body, 1_700_000_000_000L + i * 1000, true) }

    @Test
    fun everyKindOfContent_survivesBeingStoredAndReadBack() {
        inbox().map(classifier::classify).forEach { original ->
            val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(original) } }.toByteArray()
            val back = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ClassifiedMessage }
            assertEquals(original, back)
        }
    }

    @Test
    fun secondPass_readsNothingAgain_andNewOrChangedMessagesAreReadOnce() = runBlocking {
        val cache = MemoryCache()
        val sms = Sms(inbox())
        val useCase = ObserveMessagesUseCase(sms, classifier, Dispatchers.Unconfined, classificationCache = cache)

        val first = useCase().first()
        assertEquals(bodies.size, cache.saved)

        val second = useCase().first()
        assertEquals("nothing new to read", bodies.size, cache.saved)
        assertEquals(first, second)

        // The read flag changes without the text changing: no new work, but the new flag shows.
        sms.messages = inbox().map { it.copy(isRead = false) }
        assertTrue(useCase().first().none { it.message.isRead })
        assertEquals(bodies.size, cache.saved)

        // A new message is read; a deleted one is forgotten.
        sms.messages = inbox().drop(1) + SmsMessage(99, "AD-HDFCBK", "Your appointment is confirmed for tomorrow at 4 PM", 1_800_000_000_000L, true)
        useCase().first()
        assertEquals(bodies.size + 1, cache.saved)
        assertTrue(1L !in cache.entries && 99L in cache.entries)
    }
}
