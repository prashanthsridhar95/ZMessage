package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.insights.DailyDigest
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteAndDigestTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 27)
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
    private fun sms(body: String, sender: String = "AD-INFO", on: LocalDate = today) = SmsMessage(nextId++, sender, body, at(on), true)

    private class Muted(initial: Set<String>) : MutedSenderRepository {
        override val muted: StateFlow<Set<String>> = MutableStateFlow(initial)
        override fun setMuted(senderKey: String, muted: Boolean) = Unit
    }

    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    @Test
    fun mutedSender_behavesLikeMarketing_forEveryOperatorVariant() = runBlocking {
        // Different texts: the same text on the same day is treated as a duplicate and dropped.
        val inbox = listOf(sms("Your table is ready", "AD-DINEOUT"), sms("Your booking is confirmed", "VM-DINEOUT-S"), sms("Hello", "AD-OTHER"))
        val messages = ObserveMessagesUseCase(
            Sms(inbox), classifier, Dispatchers.Unconfined, mutedSenders = Muted(setOf(SenderKey.of("AD-DINEOUT")))
        )().first()

        val byFlag = messages.groupBy { it.isMuted }
        assertEquals(2, byFlag.getValue(true).size)
        assertTrue(byFlag.getValue(true).all { it.isPromotional })
        assertFalse(messages.first { it.message.sender == "AD-OTHER" }.isMuted)
    }

    @Test
    fun digest_mentionsWhatIsDueAndOn_andIsNullWhenQuiet() {
        val now = Instant.ofEpochMilli(at(today))
        val busy = listOf(
            sms("Your appointment is confirmed for tomorrow at 4:30 PM at Apollo Clinic."),
            sms("Your order ABC123456 is out for delivery today."),
            sms("Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1", "AD-HDFCBK", today.minusDays(1))
        ).map(classifier::classify)

        val digest = DailyDigest.build(busy, UserMessageState(), now, zone)
        assertNotNull(digest)
        assertTrue(digest!!.lines.any { "tomorrow" in it })
        assertTrue(digest.lines.any { "spent ₹540 yesterday" in it })

        assertNull(DailyDigest.build(listOf(classifier.classify(sms("Hello there"))), UserMessageState(), now, zone))
    }
}
