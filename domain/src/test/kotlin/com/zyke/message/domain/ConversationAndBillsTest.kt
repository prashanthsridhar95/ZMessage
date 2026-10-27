package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.conversation.ConversationBuilder
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationBuilderTest {
    private val classifier = MessageClassifier()
    private val builder = ConversationBuilder()
    private var nextId = 1L
    private val base = 1_700_000_000_000L

    private fun msg(sender: String, body: String, minutes: Long, read: Boolean = true) =
        classifier.classify(SmsMessage(nextId++, sender, body, base + minutes * 60_000, read))

    @Test
    fun senderKey_mergesOperatorPrefixesAndSuffixes() {
        assertEquals("HDFCBK", SenderKey.of("AD-HDFCBK"))
        assertEquals("HDFCBK", SenderKey.of("VM-HDFCBK-S"))
        assertEquals("HDFCBK", SenderKey.of("JD-hdfcbk-T"))
        assertEquals("9876543210", SenderKey.of("+91 98765 43210"))
        assertEquals("9876543210", SenderKey.of("09876543210"))
        assertEquals("AIRTEL", SenderKey.of("AIRTEL"))
    }

    @Test
    fun groupsBySender_newestFirst_withUnreadCounts() {
        val all = listOf(
            msg("AD-HDFCBK", "Sent Rs.500.00\nFrom HDFC Bank A/C x1234\nTo friend@ybl\nOn 12/01/25\nRef 1", 10, read = false),
            msg("VM-HDFCBK-S", "Sent Rs.100.00\nFrom HDFC Bank A/C x1234\nTo cafe@ybl\nOn 12/01/25\nRef 2", 5),
            msg("VM-SWIGGY", "Your Swiggy order is on the way", 20, read = false),
            msg("+919876543210", "hello", 1)
        ).sortedByDescending { it.message.date }

        val conversations = builder.conversations(all, UserMessageState())

        assertEquals(listOf("SWIGGY", "HDFCBK", "9876543210"), conversations.map { it.key })
        val hdfc = conversations[1]
        assertEquals(2, hdfc.messages.size)
        assertEquals(1, hdfc.unreadCount)
        assertEquals(MerchantLogo.HDFC, hdfc.merchant.logo)
        assertEquals(MerchantLogo.PERSON, conversations[2].merchant.logo)
    }

    @Test
    fun archivedAreExcluded_andSearchFiltersSenders() {
        val a = msg("AD-HDFCBK", "Rs 500 debited", 10)
        val b = msg("VM-SWIGGY", "order on the way", 5)
        val all = listOf(a, b)

        assertEquals(1, builder.conversations(all, UserMessageState(archivedIds = setOf(a.id))).size)
        assertEquals(listOf("SWIGGY"), builder.conversations(all, UserMessageState(), "order").map { it.key })
        assertEquals(listOf("HDFCBK"), builder.conversations(all, UserMessageState(), "hdfc").map { it.key })
    }

    @Test
    fun thread_isSplitByDay() {
        val day = 86_400_000L
        val first = classifier.classify(SmsMessage(1, "AD-HDFCBK", "one", base, true))
        val second = classifier.classify(SmsMessage(2, "AD-HDFCBK", "two", base + 2 * day, true))
        val other = classifier.classify(SmsMessage(3, "AD-OTHER", "x", base, true))

        val thread = builder.thread(listOf(second, first, other), "HDFCBK", UserMessageState(), ZoneId.of("UTC"))!!
        assertEquals(2, thread.sections.size)
        assertEquals(2L, thread.sections.first().items.single().newest.id)
        assertEquals(true, thread.sections.first().id is SectionId.Day)
        assertNull(builder.thread(listOf(first), "NOPE", UserMessageState(), ZoneId.of("UTC")))
    }
}

class CardBillsBuilderTest {
    private val classifier = MessageClassifier()
    private var nextId = 1L
    private val today = LocalDate.of(2025, 3, 15)
    private val now = 1_742_040_000_000L // 15 Mar 2025

    private fun hdfcBill(card: String, total: String, due: String, daysAgo: Long) = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "HDFC Bank Credit Card XX$card Statement: Total due amt: Rs.$total Min due amt: Rs.500.00 Due by:$due pay https://hdfcbk.io/$card",
            now - daysAgo * 86_400_000L, true
        )
    )

    @Test
    fun groupsByCard_latestFirst_soonestDueOnTop() {
        val bills = CardBillsBuilder.build(
            listOf(
                hdfcBill("1234", "12,345.00", "20-03-2025", 2),
                hdfcBill("1234", "9,000.00", "20-02-2025", 30),
                hdfcBill("5678", "4,000.00", "17-03-2025", 1)
            ),
            today
        )

        assertEquals(listOf("5678", "1234"), bills.map { it.accountNumber }) // due 17th before due 20th
        val card = bills[1]
        assertEquals("HDFC Card •• 1234", card.label)
        assertEquals(12345.0, card.latest.amount.toDouble(), 0.001)
        assertEquals(LocalDate.of(2025, 3, 20), card.latest.dueDate)
        assertEquals(500.0, card.latest.minimumDue!!.toDouble(), 0.001)
        assertEquals("https://hdfcbk.io/1234", card.latest.paymentLink)
        assertEquals(listOf(9000.0), card.earlier.map { it.amount.toDouble() })
    }

    @Test
    fun paidUpCards_sinkBelowCardsWithBillsStillToCome() {
        val bills = CardBillsBuilder.build(
            listOf(
                hdfcBill("1111", "1,000.00", "01-03-2025", 3),   // due date already passed
                hdfcBill("2222", "2,000.00", "25-03-2025", 10)
            ),
            today
        )
        assertEquals(listOf("2222", "1111"), bills.map { it.accountNumber })
    }
}
