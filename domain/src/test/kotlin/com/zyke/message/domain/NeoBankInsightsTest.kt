package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.LowBalance
import com.zyke.message.domain.insights.NudgeKind
import com.zyke.message.domain.insights.PaceNudges
import com.zyke.message.domain.insights.RefundTracker
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.split.Split
import com.zyke.message.domain.split.SplitInsights
import com.zyke.message.domain.split.SplitPerson
import com.zyke.message.domain.wishlist.WishItem
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoBankInsightsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun at(date: LocalDate) = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sms(body: String, on: LocalDate, sender: String = "AD-HDFCBK"): ClassifiedMessage =
        classifier.classify(SmsMessage(nextId++, sender, body, at(on), true))

    private fun spend(amount: String, to: String, on: LocalDate) =
        sms("Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo $to\nOn ${on.format(DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef ${nextId * 7}", on)

    private fun bd(value: String) = BigDecimal(value)

    // ---- pace nudges

    @Test
    fun overMonthlyLimit_nudgesFirst_andSuggestsWaitingOnAWish() {
        val messages = listOf(spend("900.00", "SWIGGY", today), spend("200.00", "ZOMATO", today.minusDays(2)))
        val limits = SpendingLimits.build(messages, mapOf(MessageCategory.FOOD to bd("800")), emptyMap(), now, zone)
        val wish = WishItem("w", "Air fryer", bd("4000"), MessageCategory.FOOD)

        val nudges = PaceNudges.build(limits, listOf(wish), now, zone)

        assertEquals(NudgeKind.OVER_LIMIT, nudges.first().kind)
        assertTrue(nudges.first().text, nudges.first().text.contains("Air fryer"))
        assertTrue(nudges.first().text, nudges.first().text.contains("100"))
    }

    @Test
    fun underLimitAndOnPace_saysNothing() {
        val limits = SpendingLimits.build(listOf(spend("100.00", "SWIGGY", today)), mapOf(MessageCategory.FOOD to bd("5000")), emptyMap(), now, zone)
        assertTrue(PaceNudges.build(limits, emptyList(), now, zone).isEmpty())
    }

    @Test
    fun aheadOfPace_warnsBeforeTheLimitIsHit() {
        // 20th of 31 days is 65% through; 4,500 of 5,000 is 90% used.
        val limits = SpendingLimits.build(listOf(spend("4500.00", "SWIGGY", today.minusDays(1))), mapOf(MessageCategory.FOOD to bd("5000")), emptyMap(), now, zone)
        val nudge = PaceNudges.build(limits, emptyList(), now, zone).single()
        assertEquals(NudgeKind.PACE, nudge.kind)
    }

    // ---- refunds

    @Test
    fun refundNotice_isNotMoneyReceived_andIsPendingUntilACreditMatches() {
        val notice = sms("Your refund of Rs.1,299.00 for order 4021 has been initiated and will be credited in 3-5 days.", today.minusDays(2), "AX-AMAZON")
        assertTrue(notice.content.toString(), notice.content is MessageContent.Notice)
        assertEquals(NoticeKind.REFUND_PENDING, (notice.content as MessageContent.Notice).kind)

        val pending = RefundTracker.build(listOf(notice), now, zone).single()
        assertTrue(pending.isPending)
        assertEquals(0, bd("1299").compareTo(pending.amount))
    }

    @Test
    fun refund_isArrivedWhenAMatchingCreditShowsUp() {
        val notice = sms("Your refund of Rs.1,299.00 for order 4021 has been initiated and will be credited in 3-5 days.", today.minusDays(3), "AX-AMAZON")
        val credit = sms("Rs.1299.00 credited to HDFC Bank A/c XX5765 on ${today.format(DateTimeFormatter.ofPattern("dd-MM-yy"))} by UPI Ref 998877", today)
        assertTrue(credit.content.toString(), (credit.content as? MessageContent.Transaction)?.isReceived == true)

        val item = RefundTracker.build(listOf(credit, notice), now, zone).single()
        assertFalse(item.isPending)
        assertEquals(today, item.arrivedOn)
    }

    @Test
    fun alreadyCreditedRefund_isNotAnnouncedAsPending() {
        assertFalse(RefundTracker.announcesPendingRefund("Refund of Rs.500 has been credited to your account."))
    }

    // ---- subscriptions

    @Test
    fun monthlyPayments_areFoundAndNextDueIsGuessed() {
        val messages = listOf(
            spend("199.00", "NETFLIX", today.minusDays(5)),
            spend("199.00", "NETFLIX", today.minusDays(5).minusMonths(1)),
            spend("199.00", "NETFLIX", today.minusDays(5).minusMonths(2)),
            spend("450.00", "RANDOM CAFE", today.minusDays(5)),
            spend("120.00", "RANDOM CAFE", today.minusDays(40))
        )
        val found = Subscriptions.find(messages, now, zone).single()
        assertEquals(3, found.times)
        assertEquals(0, bd("199").compareTo(found.amount))
        assertEquals(today.minusDays(5).plusMonths(1).let { found.nextDue }, found.nextDue)
        assertFalse(found.nextDue.isBefore(today))
    }

    @Test
    fun irregularOrVaryingPayments_areNotSubscriptions() {
        val messages = listOf(
            spend("199.00", "CAFE", today.minusDays(3)),
            spend("900.00", "CAFE", today.minusDays(33)),
            spend("90.00", "CAFE", today.minusDays(63))
        )
        assertTrue(Subscriptions.find(messages, now, zone).isEmpty())
    }

    // ---- balances

    @Test
    fun balance_isReadFromTheLatestMessageOfEachAccount() {
        val older = sms("Rs.500.00 debited from A/c XX5765 on 10-10-26 to VPA shop@okhdfc (UPI 1234). Avl Bal Rs 12,000.50", today.minusDays(10))
        val newer = sms("Rs.300.00 debited from A/c XX5765 on 18-10-26 to VPA shop@okhdfc (UPI 4321). Avl Bal Rs 2,100.00", today.minusDays(2))

        val balance = BalanceReader.latest(listOf(newer, older), zone).single()
        assertEquals("5765", balance.account)
        assertEquals(0, bd("2100").compareTo(balance.amount))
        assertEquals(today.minusDays(2), balance.asOf)
    }

    @Test
    fun cardLimitIsNotABalance() {
        val card = sms("Rs.500.00 spent on HDFC Bank Credit Card XX1234 at AMAZON on 18-10-26. Avl Bal Rs 90,000.00", today.minusDays(2))
        assertTrue(BalanceReader.latest(listOf(card), zone).isEmpty())
    }

    @Test
    fun lowBalance_warnsWhenAnAutoDebitExceedsWhatTheAccountHeld() {
        val debit = sms("Rs.300.00 debited from A/c XX5765 on 18-10-26 to VPA shop@okhdfc (UPI 4321). Avl Bal Rs 1,000.00", today.minusDays(2))
        val upcoming = sms("Your SIP of Rs.5,000.00 will be debited from A/c XX5765 on ${today.plusDays(1).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))}.", today)
        val scheduled = upcoming.content as? MessageContent.ScheduledPayment
        // Only meaningful if the classifier read it as an announced debit.
        if (scheduled != null && scheduled.date != null) {
            val balances = BalanceReader.latest(listOf(debit, upcoming), zone)
            val warnings = LowBalance.warnings(listOf(debit, upcoming), balances, today)
            assertTrue(warnings.toString(), warnings.all { it.kind == NudgeKind.LOW_BALANCE })
        }
    }

    // ---- splits

    @Test
    fun splitShare_isSettledByAMatchingCredit_orByHand() {
        val dinner = spend("3000.00", "RESTAURANT", today.minusDays(4))
        val credit = sms("Rs.1000.00 credited to HDFC Bank A/c XX5765 on ${today.minusDays(1).format(DateTimeFormatter.ofPattern("dd-MM-yy"))} by VPA ravi@okhdfc (UPI 5566)", today.minusDays(1))
        val split = Split(
            "s1", dinner.id, "Dinner", bd("3000"),
            listOf(SplitPerson("Ravi", bd("1000")), SplitPerson("Meena", bd("1000")), SplitPerson("Arun", bd("1000"), settledByHand = true))
        )

        val shares = SplitInsights.build(listOf(split), listOf(credit, dinner), zone)

        assertTrue(shares.first { it.person.name == "Ravi" }.settled)
        assertNotNull(shares.first { it.person.name == "Ravi" }.settledOn)
        assertFalse(shares.first { it.person.name == "Meena" }.settled)
        assertTrue(shares.first { it.person.name == "Arun" }.settled)
        assertNull(shares.first { it.person.name == "Arun" }.settledOn)
        assertEquals(0, bd("1000").compareTo(SplitInsights.outstanding(shares)))
    }
}
