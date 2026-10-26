package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedBuilderTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2025, 3, 15, 12, 0, 0, 0, zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun at(minutesAgo: Long = 0, daysAgo: Long = 0) =
        now.minusSeconds(minutesAgo * 60 + daysAgo * 86_400).toEpochMilli()

    private fun msg(body: String, sender: String = "AD-TEST", minutesAgo: Long = 0, daysAgo: Long = 0) =
        classifier.classify(SmsMessage(nextId++, sender, body, at(minutesAgo, daysAgo), true))

    private fun build(
        messages: List<com.zyke.message.domain.model.ClassifiedMessage>,
        filter: MessageFilter = MessageFilter.All,
        user: UserMessageState = UserMessageState(),
        query: String = ""
    ) = FeedBuilder.build(messages.sortedByDescending { it.message.date }, user, filter, query, now, zone)

    private val hdfcSpend = "Sent Rs.500.00\nFrom HDFC Bank A/C x1234\nTo friend@ybl\nOn 12/01/25\nRef 123456"

    @Test
    fun promoSenderAndHints_areFlaggedAndHiddenFromAll() {
        val promo = msg("Flat Rs 200 off on your next order. T&C apply", sender = "AD-SHOPXY-P")
        val normal = msg("Your Swiggy order is on the way", sender = "SWIGGY")
        assertTrue(promo.isPromotional)
        assertFalse(normal.isPromotional)

        val feed = build(listOf(promo, normal))
        assertEquals(1, feed.visibleCount)
        assertTrue(SmartFilter.PROMOS in feed.availableFilters)

        val promos = build(listOf(promo, normal), MessageFilter.Smart(SmartFilter.PROMOS))
        assertEquals(1, promos.visibleCount)
    }

    @Test
    fun otpsFromSameSenderAndDay_areGrouped() {
        val a = msg("111111 is your OTP", sender = "AD-HDFCBK", minutesAgo = 1)
        val b = msg("222222 is your OTP", sender = "AD-HDFCBK", minutesAgo = 2)
        val other = msg("333333 is your OTP", sender = "AD-AXISBK", minutesAgo = 3)

        val items = build(listOf(a, b, other)).sections.single().items
        assertEquals(2, items.size)
        val group = items.filterIsInstance<FeedItem.Group>().single()
        assertEquals(2, group.messages.size)
        assertEquals(a.id, group.newest.id)
        assertEquals(1, group.hiddenCount)
    }

    @Test
    fun pinnedMessages_moveToTopSection_andArchivedAreHidden() {
        val old = msg("Your Swiggy order is on the way", sender = "SWIGGY", daysAgo = 5)
        val recent = msg("Hello there", sender = "+911234567890")
        val archived = msg("Another message", sender = "+911234567891")

        val user = UserMessageState(pinnedIds = setOf(old.id), archivedIds = setOf(archived.id))
        val feed = build(listOf(old, recent, archived), user = user)

        assertEquals(SectionId.Pinned, feed.sections.first().id)
        assertEquals(old.id, feed.sections.first().items.single().newest.id)
        assertEquals(2, feed.visibleCount)
        assertEquals(1, build(listOf(old, recent, archived), MessageFilter.Smart(SmartFilter.ARCHIVED), user).visibleCount)
    }

    @Test
    fun search_ignoresHidingAndMatchesAmountsAndMerchants() {
        val spend = msg(hdfcSpend)
        val promo = msg("Flat Rs 200 off T&C apply", sender = "AD-SHOPXY-P")

        assertEquals(1, build(listOf(spend, promo), query = "friend@ybl").visibleCount)
        assertEquals(1, build(listOf(spend, promo), query = "500").visibleCount)
        assertEquals(1, build(listOf(spend, promo), query = "flat").visibleCount)
    }

    @Test
    fun smartFilters_bucketByContent() {
        val otp = msg("123456 is your OTP")
        val spend = msg(hdfcSpend)
        val tag = msg("INR 65 toll paid from HDFC Bank Tag 1234 for vehicle no. KA01AB1234 at Hosur Toll on 12-Jan-25 10:30. Avbl. Bal.: INR435")
        val all = listOf(otp, spend, tag)

        assertEquals(1, build(all, MessageFilter.Smart(SmartFilter.OTP)).visibleCount)
        assertEquals(1, build(all, MessageFilter.Smart(SmartFilter.MONEY)).visibleCount)
        assertEquals(1, build(all, MessageFilter.Smart(SmartFilter.TRAVEL)).visibleCount)
        assertEquals(1, build(all, MessageFilter.Category(MessageCategory.FASTAG)).visibleCount)
        assertFalse(SmartFilter.BILLS in build(all).availableFilters)
    }

    @Test
    fun attention_freshOtpExpiresAfterTenMinutes() {
        val fresh = build(listOf(msg("123456 is your OTP", minutesAgo = 3)))
        assertNotNull(fresh.attention.filterIsInstance<AttentionItem.FreshOtp>().singleOrNull())

        val stale = build(listOf(msg("123456 is your OTP", minutesAgo = 30)))
        assertTrue(stale.attention.isEmpty())
    }

    @Test
    fun attention_billsUseDueDate_andCanBeDismissed() {
        val upcoming = msg("HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:20-03-2025 pay https://hdfcbk.io/x", daysAgo = 5)
        val paidLong = msg("HDFC Bank Credit Card XX9999 Statement: Total due amt: Rs.500.00 Min due amt: Rs.100.00 Due by:01-03-2025 pay https://hdfcbk.io/y", daysAgo = 14)

        val feed = build(listOf(upcoming, paidLong))
        val bill = feed.attention.single() as AttentionItem.BillDue
        assertEquals(LocalDate.of(2025, 3, 20), bill.dueDate)
        assertEquals("12345", bill.amount)

        val dismissed = build(listOf(upcoming, paidLong), user = UserMessageState(dismissedAttention = setOf(bill.key)))
        assertTrue(dismissed.attention.isEmpty())
    }

    @Test
    fun attention_lowFastTagOnlyIfNewestSaysSo() {
        val low = msg("Your FASTag vehicle KA01AB1234 is low on balance", daysAgo = 2)
        assertEquals(1, build(listOf(low)).attention.size)

        val toppedUp = msg("Recharge successful: KA01AB1234 FASTag XYZ Credited with Rs 500 Ref: 123 Avbl Bal:Rs 900", daysAgo = 1)
        assertTrue(build(listOf(low, toppedUp)).attention.isEmpty())
    }

    @Test
    fun spendSummary_sumsSentMoneyThisMonth() {
        val first = msg(hdfcSpend, daysAgo = 1)
        val second = msg(hdfcSpend.replace("500.00", "250.50"), daysAgo = 2)
        val lastMonth = msg(hdfcSpend, daysAgo = 30)
        val received = msg("Credit Alert!\nRs.9000.00 credited to HDFC Bank A/c xx9876 on 01-02-25 from VPA friend@okaxis (UPI 123456789)")

        val summary = build(listOf(first, second, lastMonth, received)).summary
        assertNotNull(summary)
        assertEquals("750.50", summary!!.total.toPlainString())
        assertEquals(MessageCategory.UPI_PAYMENT, summary.byCategory.single().category)
        assertNull(build(listOf(lastMonth)).summary)
    }

    @Test
    fun cardBillParsing_carriesDueDateAndMinimum() {
        val bill = msg("Axis Bank Credit Card no. XX5678 E-Statement of your Axis Bank Credit Card no. XX5678 has been generated. Total amount due: INR Dr. 1500.00. Minimum amt due: INR Dr. 150.00, Due date: 25-03-25. Visit https://axis.example/x to view / download.")
        val content = bill.content as MessageContent.CardBill
        assertEquals(LocalDate.of(2025, 3, 25), content.dueDate)
        assertEquals("150", content.minimumDue)
        assertEquals("https://axis.example/x", content.paymentLink)
    }
}
