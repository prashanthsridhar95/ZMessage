package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.export.Exports
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.FeedItem
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.feed.StatusFilter
import com.zyke.message.domain.insights.AccountBalance
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.PaymentGroups
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.ledger.SamePaymentDecisions
import com.zyke.message.domain.ledger.SamePaymentKind
import com.zyke.message.domain.ledger.SamePaymentLink
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One payment, shown once: the messages the app is certain repeat a bank debit (a booking confirmation, a gateway's text, a
 * second bank alert) sit inside the debit's row. Nothing is deleted; identical alerts and unsure links are separate rows.
 */
class PaymentGroupsTest {
    private val zone = ZoneId.of("UTC")
    private val classifier = MessageClassifier()
    private val now = Instant.parse("2026-10-28T20:00:00Z")
    private var nextId = 1L

    private fun at(day: Int, hour: Int, minute: Int) = LocalDate.of(2026, 10, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun sms(sender: String, body: String, hour: Int, minute: Int, day: Int = 28) =
        classifier.classify(SmsMessage(nextId++, sender, body, at(day, hour, minute), false))

    private fun upiDebit(amount: String, payee: String, ref: String, hour: Int, minute: Int, day: Int = 28) =
        sms("AD-HDFCBK", "Rs $amount debited from A/c XX5765 on $day-10-26 to VPA $payee (UPI Ref $ref). Not you? Call 18002586161", hour, minute, day)

    private fun sentAlert(amount: String, to: String, ref: String, hour: Int, minute: Int) =
        sms("AD-HDFCBK", "Amt Sent Rs.$amount From HDFC Bank A/C *5765 To $to On 28-10 Ref $ref Not You? Call 18002586161", hour, minute)

    private fun balanceAlert(amount: String, ref: String, hour: Int, minute: Int) =
        sms("AD-HDFCBK", "Update! INR $amount debited from HDFC Bank A/c XX5765 on 28-OCT-26. UPI Ref No $ref. Avl bal INR 12,001.00", hour, minute)

    private fun irctcBooking(hour: Int, minute: Int, day: Int = 28) =
        sms("VM-IRCTCI", "Booking confirmed. Trip #4147762019 on 15-11-2026 for train 12627. Wish you a happy journey. -IRCTC", hour, minute, day)

    private fun amazonOrder(amount: String, hour: Int, minute: Int) =
        sms("VM-AMAZON", "Order placed! Your order #402-1234567-1234567 of Rs. $amount has been placed. Track: amzn.in/x", hour, minute)

    private fun razorpay(amount: String, hour: Int, minute: Int, ref: String? = null) =
        sms("VM-RZRPAY", "Payment of Rs.$amount towards Netflix successfully made on 28 October 2026." + (ref?.let { " Ref $it" } ?: "") + " - Razorpay", hour, minute)

    private fun merchant(amount: String, hour: Int, minute: Int, ref: String? = null) =
        sms("VM-NETFLX", "Dear customer, your payment of Rs.$amount to Netflix has been received." + (ref?.let { " Ref $it." } ?: "") + " Thank you", hour, minute)

    private fun link(list: List<ClassifiedMessage>, decisions: SamePaymentDecisions = SamePaymentDecisions()) =
        SamePayment.apply(list.sortedByDescending { it.message.date }, decisions, zone)

    private fun spent(list: List<ClassifiedMessage>): BigDecimal = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.spend(m) ?: BigDecimal.ZERO) }
    private fun assertMoney(expected: Long, actual: BigDecimal) = assertEquals("expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual))
    private fun ClassifiedMessage.linkIn(all: List<ClassifiedMessage>) = all.single { it.id == id }.samePayment

    private fun build(list: List<ClassifiedMessage>, filter: MessageFilter = MessageFilter.All, user: UserMessageState = UserMessageState(), query: String = "") =
        FeedBuilder.build(list.sortedByDescending { it.message.date }, user, filter, query, now, zone)

    private fun rows(feed: com.zyke.message.domain.feed.Feed) = feed.sections.flatMap { it.items }

    // ---- the IRCTC case

    @Test
    fun irctcDebitAndBookingConfirmation_areOneRow() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val all = link(listOf(debit, booking))

        val link = booking.linkIn(all)
        assertEquals(SamePaymentKind.CERTAIN, link?.kind)
        assertEquals(debit.id, link?.primaryId)
        assertNull(debit.linkIn(all))
        assertMoney(2000, spent(all))

        val groups = PaymentGroups.of(all)
        assertEquals(listOf(booking.id), groups.followersOf(debit.id).map { it.id })
        assertEquals(debit.id, groups.anchorOf(booking.id))

        val feed = build(all)
        val items = rows(feed)
        assertEquals(1, items.size)
        val row = items.single() as FeedItem.Single
        assertEquals(debit.id, row.message.id)
        assertEquals("m-${debit.id}", row.key)
        assertEquals(listOf(booking.id), row.related.map { it.id })
        assertEquals(1, feed.visibleCount)
    }

    @Test
    fun aBookingWithoutAmountOrReference_needsTheMerchantAndLessThanTenMinutes() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val late = irctcBooking(13, 40)
        assertNull("14 minutes apart is too far without an amount or reference", late.linkIn(link(listOf(debit, late))))
        val other = upiDebit("2000.00", "someshop@ybl", "6123456780", 13, 26)
        val booking = irctcBooking(13, 28)
        assertNull("another merchant's debit is no evidence", booking.linkIn(link(listOf(other, booking))))
    }

    @Test
    fun anOrderConfirmationThatStatesTheSameAmount_followsTheDebitWithinFifteenMinutes() {
        val debit = upiDebit("1499.00", "amazonpay@apl", "7123456789", 12, 0)
        val order = amazonOrder("1,499.00", 12, 9)
        val all = link(listOf(debit, order))
        assertEquals(debit.id, order.linkIn(all)?.primaryId)
        assertEquals(SamePaymentKind.CERTAIN, order.linkIn(all)?.kind)

        val otherAmount = amazonOrder("1,299.00", 12, 9)
        assertNull(otherAmount.linkIn(link(listOf(debit, otherAmount))))
        val tooLate = amazonOrder("1,499.00", 12, 20)
        assertNull(tooLate.linkIn(link(listOf(debit, tooLate))))
    }

    // ---- gateway and bank

    @Test
    fun gatewayAndMerchantTexts_withTheDebit_areOneRowAndCountOnce() {
        val debit = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        val gateway = razorpay("987", 10, 1, ref = "6123456789")
        val receipt = merchant("987.00", 10, 2, ref = "6123456789")
        val all = link(listOf(debit, gateway, receipt))

        assertMoney(987, spent(all))
        val feed = build(all)
        val row = rows(feed).single() as FeedItem.Single
        assertEquals(debit.id, row.message.id)
        assertEquals(setOf(gateway.id, receipt.id), row.related.map { it.id }.toSet())
        // The related ones are listed oldest first.
        assertEquals(listOf(gateway.id, receipt.id), row.related.map { it.id })
    }

    // ---- what must stay apart

    @Test
    fun fiveIdenticalAlerts_stayFiveRows() {
        val alerts = (0 until 5).map { upiDebit("500.00", "friend@ybl", "6123456789", 10, it) }
        val all = link(alerts)
        assertTrue(all.all { it.samePayment == null })
        assertTrue(PaymentGroups.of(all).isEmpty)
        assertEquals(5, rows(build(all)).size)
        assertMoney(2500, spent(all))
    }

    @Test
    fun sameWordingEvenWithOneReference_isNeverPaired() {
        val a = upiDebit("500.00", "friend@ybl", "6123456789", 10, 0)
        val b = upiDebit("500.00", "friend@ybl", "6123456789", 10, 1)
        val all = link(listOf(a, b))
        assertTrue(all.all { it.samePayment == null })
        assertEquals(2, rows(build(all)).size)
    }

    @Test
    fun twoEqualPayments_withTwoDebits_stayTwoGroups() {
        val d1 = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456781", 10, 0)
        val d2 = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456782", 10, 5)
        val b1 = irctcBooking(10, 1)
        val b2 = irctcBooking(10, 6)
        val all = link(listOf(d1, d2, b1, b2))
        assertEquals(d1.id, b1.linkIn(all)?.primaryId)
        assertEquals(d2.id, b2.linkIn(all)?.primaryId)
        val items = rows(build(all))
        assertEquals(2, items.size)
        assertTrue(items.all { (it as FeedItem.Single).related.size == 1 })
        assertMoney(4000, spent(all))
    }

    @Test
    fun oneConfirmationFittingTwoDebitsAboutEquallyWell_isNotGuessed() {
        val d1 = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456781", 10, 0)
        val d2 = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456782", 10, 2)
        val booking = irctcBooking(10, 1)
        assertNull(booking.linkIn(link(listOf(d1, d2, booking))))
    }

    @Test
    fun aDebitAndTheCreditAtTheReceiver_areNeverOnePayment() {
        val debit = upiDebit("987.00", "friend@ybl", "6123456789", 10, 0)
        val credit = sms("AX-ICICIB", "Rs 987.00 credited to your A/c XX9876 on 28-10-26 by UPI Ref 6123456789 from Rahul. Avl Bal Rs 5,000.00", 10, 1)
        val all = link(listOf(debit, credit))
        assertTrue(all.all { it.samePayment == null })
        assertEquals(2, rows(build(all)).size)
    }

    // ---- what the person said, and what is unsure

    @Test
    fun notTheSamePayment_ungroups_andYesGroupsAnUnsureOne() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val said = link(listOf(debit, booking), SamePaymentDecisions(no = setOf(booking.id)))
        assertTrue(said.all { it.samePayment == null })
        assertEquals(2, rows(build(said)).size)
        assertMoney(2000, spent(said))

        val gateway = razorpay("987", 10, 1)
        val d = upiDebit("987.00", "netflix@icici", "6123456780", 10, 0)
        val unsure = link(listOf(d, gateway))
        assertEquals(SamePaymentKind.SUSPECTED, gateway.linkIn(unsure)?.kind)
        assertTrue("an unsure link is not grouped", PaymentGroups.of(unsure).isEmpty)
        assertEquals(2, rows(build(unsure)).size)
        assertEquals(listOf(gateway.id), ReviewQueues.build(unsure, SetAsides(), zone, now).samePayments.map { it.id })

        val yes = link(listOf(d, gateway), SamePaymentDecisions(yes = mapOf(gateway.id to d.id)))
        assertEquals(1, rows(build(yes)).size)
    }

    // ---- two bank messages about one transaction

    @Test
    fun twoBankAlertsWithOneReference_areOnePayment_andTheOneWithTheBalanceIsKept() {
        val plain = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        val withBalance = balanceAlert("987.00", "6123456789", 10, 1)
        val all = link(listOf(plain, withBalance))
        // The alert with the balance is the payment even though it came later; the other is its repeat.
        assertEquals(withBalance.id, plain.linkIn(all)?.primaryId)
        assertEquals(SamePaymentKind.CERTAIN, plain.linkIn(all)?.kind)
        assertNull(withBalance.linkIn(all))
        assertMoney(987, spent(all))
        val row = rows(build(all)).single() as FeedItem.Single
        assertEquals(withBalance.id, row.message.id)
        assertEquals(listOf(plain.id), row.related.map { it.id })
    }

    @Test
    fun twoBankAlertsWithoutBalances_theEarlierIsKept() {
        val first = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        val second = sentAlert("987.00", "NETFLIX", "6123456789", 10, 1)
        val all = link(listOf(first, second))
        assertEquals(first.id, second.linkIn(all)?.primaryId)
        assertMoney(987, spent(all))
    }

    @Test
    fun bankAlertsWithDifferentReferences_orFarApart_orOtherAccounts_areNotPaired() {
        val a = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        assertNull(sentAlert("987.00", "NETFLIX", "9999999999", 10, 1).let { b -> b.linkIn(link(listOf(a, b))) })
        val late = sentAlert("987.00", "NETFLIX", "6123456789", 11, 0)
        assertEquals("a shared reference links within half an hour only", null, late.linkIn(link(listOf(a, late))))
        val other = sms("AD-HDFCBK", "Amt Sent Rs.987.00 From HDFC Bank A/C *4321 To NETFLIX On 28-10 Ref 6123456789 Not You? Call 18002586161", 10, 1)
        assertNull(other.linkIn(link(listOf(a, other))))
    }

    @Test
    fun differentWordingWithoutAReference_isOnlyAQuestion() {
        val a = sms("AD-HDFCBK", "Rs 987.00 debited from A/c XX5765 on 28-10-26 to VPA netflix@icici. Not you? Call 18002586161", 10, 0)
        val b = sms("AD-HDFCBK", "Amt Sent Rs.987.00 From HDFC Bank A/C *5765 To NETFLIX On 28-10 Not You? Call 18002586161", 10, 1)
        val all = link(listOf(a, b))
        assertEquals(SamePaymentKind.SUSPECTED, b.linkIn(all)?.kind)
        assertMoney(1974, spent(all))
        assertTrue(PaymentGroups.of(all).isEmpty)
        assertEquals(2, rows(build(all)).size)
        val late = sms("AD-HDFCBK", "Amt Sent Rs.987.00 From HDFC Bank A/C *5765 To NETFLIX On 28-10 Not You? Call 18002586161", 10, 4)
        assertNull(late.linkIn(link(listOf(a, late))))
    }

    @Test
    fun linkedBankAlerts_doNotDoubleCountTheBalance_norDropTheDebit() {
        val reading = AccountBalance("5765", "HDFC", BigDecimal(10_000), LocalDate.of(2026, 9, 30), 0L, at(27, 9, 0))
        val first = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        val second = sentAlert("987.00", "NETFLIX", "6123456789", 10, 1)
        val raw = listOf(first, second).sortedByDescending { it.message.date }
        val unlinked = BalanceReader.movement(raw, reading)
        val linked = BalanceReader.movement(link(listOf(first, second)), reading)
        assertMoney(-1974, unlinked.net)
        assertMoney(-987, linked.net)
        // A different payment of the same amount a day later is still counted.
        val third = upiDebit("987.00", "netflix@icici", "6123456780", 10, 0, day = 28)
        val fourth = sms("AD-HDFCBK", "Rs 987.00 debited from A/c XX5765 on 28-10-26 to VPA netflix@icici (UPI Ref 6123456000). Not you? Call 18002586161", 15, 0)
        assertMoney(-1974, BalanceReader.movement(link(listOf(third, fourth)), reading).net)
    }

    // ---- the list

    @Test
    fun theGroupLeadsItsDay_andFollowersAreNeverRowsOfTheirOwn() {
        val yesterday = upiDebit("300.00", "cafe@ybl", "6123456700", 9, 0, day = 27)
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val all = link(listOf(yesterday, debit, booking))
        val feed = build(all)
        assertEquals(2, feed.sections.size)
        val day = feed.sections.first().id as SectionId.Day
        assertEquals(LocalDate.of(2026, 10, 28), day.date)
        val keys = rows(feed).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(keys.contains("m-${booking.id}"))
        for (tab in listOf(SmartFilter.ALL, SmartFilter.MONEY)) {
            val tabRows = rows(build(all, MessageFilter(tab)))
            assertFalse(tab.name, tabRows.any { it.key == "m-${booking.id}" })
            assertTrue(tab.name, tabRows.any { it.key == "m-${debit.id}" })
        }
        // In the Travel tab the debit is not shown, so the booking is its own row there.
        val travel = rows(build(all, MessageFilter(SmartFilter.TRAVEL)))
        assertEquals(listOf("m-${booking.id}"), travel.map { it.key })
    }

    @Test
    fun searchFindingAFollower_opensItsGroup() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val other = upiDebit("300.00", "cafe@ybl", "6123456700", 9, 0)
        val all = link(listOf(debit, booking, other))
        val feed = build(all, query = "happy journey")
        val found = rows(feed)
        assertEquals(listOf("m-${debit.id}"), found.map { it.key })
        assertEquals(listOf(booking.id), (found.single() as FeedItem.Single).related.map { it.id })
        assertEquals(1, feed.visibleCount)
        // Both the payment and a repeat match: still one row.
        assertEquals(1, rows(build(all, query = "irctc")).size)
    }

    @Test
    fun unreadFilter_keepsAPaymentWhoseRepeatIsUnread_andReadAndUnreadStateIsTheGroups() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val all = link(listOf(debit, booking))
        val user = UserMessageState(readIds = setOf(debit.id))
        val unread = build(all, MessageFilter(status = StatusFilter.UNREAD), user)
        val items = rows(unread)
        assertEquals(listOf("m-${debit.id}"), items.map { it.key })
        assertEquals(listOf(booking.id), (items.single() as FeedItem.Single).related.map { it.id })
        // Everything read: nothing is left in the unread list.
        val read = build(all, MessageFilter(status = StatusFilter.UNREAD), UserMessageState(readIds = setOf(debit.id, booking.id)))
        assertTrue(rows(read).isEmpty())
    }

    @Test
    fun archivedOrPinnedFollowers_staySeparateRows() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val all = link(listOf(debit, booking))
        val pinned = build(all, user = UserMessageState(pinnedIds = setOf(booking.id)))
        assertEquals(2, pinned.sections.sumOf { it.items.size })
        val archived = build(all, user = UserMessageState(archivedIds = setOf(booking.id)))
        assertEquals(listOf("m-${debit.id}"), rows(archived).map { it.key })
        assertTrue((rows(archived).single() as FeedItem.Single).related.isEmpty())
    }

    @Test
    fun theChipsCountRows_notMessages() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val feed = build(link(listOf(debit, booking)))
        assertEquals(1, feed.counts[SmartFilter.ALL])
        assertEquals(1, feed.counts[SmartFilter.MONEY])
        assertEquals(1, feed.counts[SmartFilter.TRAVEL])
    }

    // ---- the group itself

    @Test
    fun aGroupHoldsAtMostSixFollowers_theNearestInTime() {
        val debit = upiDebit("500.00", "shop@ybl", "6123456789", 10, 0)
        val template = razorpay("500", 10, 1)
        val followers = (1..8).map { i ->
            template.copy(
                message = template.message.copy(id = 100L + i, date = at(28, 10, i)),
                samePayment = SamePaymentLink(SamePaymentKind.CERTAIN, debit.id, "HDFC debit", "ref", "ref")
            )
        }
        val groups = PaymentGroups.of(listOf(debit) + followers)
        assertEquals(PaymentGroups.MAX_FOLLOWERS, groups.followersOf(debit.id).size)
        assertEquals((101L..106L).toList(), groups.followersOf(debit.id).map { it.id })
        assertFalse(groups.isFollower(107L))
        assertTrue(groups.isFollower(101L))
    }

    @Test
    fun aRepeatWhosePaymentIsGone_orIsItselfARepeat_isARowOfItsOwn() {
        val debit = upiDebit("500.00", "shop@ybl", "6123456789", 10, 0)
        val orphan = razorpay("500", 10, 1).copy(samePayment = SamePaymentLink(SamePaymentKind.CERTAIN, 999L, "HDFC debit", "x", "ref"))
        assertTrue(PaymentGroups.of(listOf(debit, orphan)).isEmpty)
        val a = razorpay("500", 10, 2).copy(samePayment = SamePaymentLink(SamePaymentKind.CERTAIN, debit.id, "HDFC debit", "x", "ref"))
        val chained = merchant("500", 10, 3).copy(samePayment = SamePaymentLink(SamePaymentKind.CERTAIN, a.id, "x", "x", "ref"))
        val groups = PaymentGroups.of(listOf(debit, a, chained))
        assertEquals(listOf(a.id), groups.followersOf(debit.id).map { it.id })
        assertFalse(groups.isFollower(chained.id))
    }

    @Test
    fun theCsvKeepsEveryMessage_andSaysWhichRepeatsWhich() {
        val debit = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        val gateway = sms("VM-PHONPE", "Paid Rs. 987 to Netflix via PhonePe. Ref 6123456789", 10, 1)
        val lines = Exports.transactionsCsv(link(listOf(debit, gateway)), zone).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains(",counted_in_totals,same_payment,"))
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("main, 1 related") })
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("repeat of ") && it.contains("no: same payment") })
    }

    @Test
    fun globalSearchFindingARepeat_showsAndOpensThePayment_once() {
        val debit = upiDebit("2000.00", "irctc.ticketing@sbi", "6123456789", 13, 26)
        val booking = irctcBooking(13, 30)
        val all = link(listOf(debit, booking))
        val results = com.zyke.message.domain.search.GlobalSearch.search(
            "happy journey", com.zyke.message.domain.search.SearchSources(messages = all), today = LocalDate.of(2026, 10, 28), zone = zone
        )
        val hits = results.groups.flatMap { it.hits }
        assertEquals(listOf(debit.id), hits.mapNotNull { it.messageId })
        val both = com.zyke.message.domain.search.GlobalSearch.search(
            "irctc", com.zyke.message.domain.search.SearchSources(messages = all), today = LocalDate.of(2026, 10, 28), zone = zone
        ).groups.flatMap { it.hits }.mapNotNull { it.messageId }
        assertEquals(listOf(debit.id), both)
    }

    @Test
    fun theSenderThread_foldsRepeatsOfTheSameSender_andKeepsOtherSendersOwnMessages() {
        val first = upiDebit("987.00", "netflix@icici", "6123456789", 10, 0)
        val second = sentAlert("987.00", "NETFLIX", "6123456789", 10, 1)
        val gateway = razorpay("987", 10, 2, ref = "6123456789")
        val all = link(listOf(first, second, gateway)).sortedByDescending { it.message.date }
        val builder = com.zyke.message.domain.conversation.ConversationBuilder()
        val hdfc = builder.thread(all, "HDFCBK", UserMessageState(), zone)!!
        val hdfcRows = hdfc.sections.flatMap { it.items }.map { it as FeedItem.Single }
        assertEquals(listOf(first.id), hdfcRows.map { it.message.id })
        // The row carries every repeat (the other bank alert and the gateway text), as the timeline does.
        assertEquals(setOf(second.id, gateway.id), hdfcRows.single().related.map { it.id }.toSet())
        val razor = builder.thread(all, "RZRPAY", UserMessageState(), zone)!!
        assertEquals(listOf(gateway.id), razor.sections.flatMap { it.items }.map { it.newest.id })
    }
}
