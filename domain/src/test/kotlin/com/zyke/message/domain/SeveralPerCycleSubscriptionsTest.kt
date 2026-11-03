package com.zyke.message.domain

import com.zyke.message.domain.insights.CancelledCharges
import com.zyke.message.domain.insights.SubscriptionOverview
import com.zyke.message.domain.insights.SubscriptionState
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A payee charged several times each cycle by separate standing orders is several subscriptions. */
class SeveralPerCycleSubscriptionsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 25)
    private val now = today.atTime(9, 0).atZone(zone).toInstant()
    private var nextId = 1L
    private val bajaj = "Bajaj Finserv Mutual Fund"

    private fun sms(payee: String, amount: String, on: LocalDate, minute: Int = 0, body: String, category: MessageCategory = MessageCategory.INVESTMENTS) =
        ClassifiedMessage(
            message = SmsMessage(nextId++, "AD-HDFCBK", body, on.atTime(10, minute).atZone(zone).toInstant().toEpochMilli(), true),
            category = category,
            merchant = Merchant(payee, MerchantLogo.UPI, category),
            content = MessageContent.Transaction(BankName.HDFC, payee, amount, "5765", false, false)
        )

    private fun alert(on: LocalDate, minute: Int = 0, umrn: String = "HDFC0000000024044009", payee: String = bajaj, amount: String = "2000.00") =
        sms(payee, amount, on, minute, "PAYMENT ALERT! INR $amount deducted from HDFC Bank A/C No 5765 towards $payee UMRN: $umrn")

    private fun sips(on: LocalDate, count: Int = 5) = (0 until count).map { alert(on, it) }

    private val thisMonth = LocalDate.of(2026, 10, 10)
    private val lastMonth = LocalDate.of(2026, 9, 10)
    private val twoAgo = LocalDate.of(2026, 8, 10)

    @Test fun fiveIdenticalSipsInTwoMonthsAreFiveEntries() {
        val messages = sips(lastMonth) + sips(thisMonth)
        val subs = Subscriptions.find(messages, now, zone)
        assertEquals(5, subs.size)
        assertTrue(subs.all { it.amount == BigDecimal(2000) && it.ofCount == 5 })
        assertEquals(listOf(1, 2, 3, 4, 5), subs.map { it.ordinal }.sorted())
        assertEquals(5, subs.map { it.key }.toSet().size)
        assertEquals("#2 of 5", subs.first { it.ordinal == 2 }.suffix)
        val overview = SubscriptionOverview.build(subs, emptyMap(), emptyMap(), today, messages, zone)
        assertEquals(5, overview.activeCount)
        assertEquals(BigDecimal(10000), overview.monthlyTotal)
        assertEquals(BigDecimal(120000), overview.yearlyTotal)
        assertTrue(overview.lines.all { it.yearly == BigDecimal(24000) })
    }

    @Test fun oneMonthOfHistoryWithAMandateKeywordIsFiveEntries() {
        assertEquals(5, Subscriptions.find(sips(thisMonth), now, zone).size)
    }

    @Test fun oneMonthWithoutAMandateSignIsNothing() {
        val shop = (0 until 5).map { sms("Corner Shop", "2000.00", thisMonth, it, "Rs 2000.00 debited to Corner Shop", MessageCategory.SHOPPING) }
        assertTrue(Subscriptions.find(shop, now, zone).isEmpty())
    }

    @Test fun oneMonthOfDifferentAmountsOrHoursIsNotSplit() {
        val differing = (0 until 5).map { alert(thisMonth, it, amount = "${2000 + it * 500}.00") }
        assertTrue(Subscriptions.find(differing, now, zone).isEmpty())
        val spread = (0 until 5).map { sms(bajaj, "2000.00", thisMonth, 0, "UMRN: X1234567890 SIP").copy(message = SmsMessage(nextId++, "AD", "SIP UMRN HDFC000000$it", thisMonth.atTime(it * 4, 0).atZone(zone).toInstant().toEpochMilli(), true)) }
        assertTrue(Subscriptions.find(spread, now, zone).isEmpty())
    }

    @Test fun anOrdinaryMonthlySubscriptionStaysOne() {
        val netflix = listOf(twoAgo, lastMonth, thisMonth).map { sms("Netflix", "649.00", it, body = "Rs 649.00 debited to Netflix", category = MessageCategory.ENTERTAINMENT) }
        val subs = Subscriptions.find(netflix, now, zone)
        assertEquals(1, subs.size)
        assertEquals("netflix", subs.single().key)
        assertEquals("", subs.single().suffix)
    }

    @Test fun severalOneOffPaymentsInAMonthAreNotSplit() {
        val shop = listOf(5, 9, 12, 20).map { d -> sms("Amazon", "${100 * d}.00", thisMonth.withDayOfMonth(d), body = "Rs debited to Amazon", category = MessageCategory.SHOPPING) } +
            listOf(2, 7).map { d -> sms("Amazon", "${70 * d}.00", lastMonth.withDayOfMonth(d), body = "Rs debited to Amazon", category = MessageCategory.SHOPPING) }
        assertTrue(Subscriptions.find(shop, now, zone).isEmpty())
    }

    @Test fun twoPayeesWithTheSameAmountStaySeparate() {
        val other = (sips(lastMonth) + sips(thisMonth)).map { it }
        val second = (0 until 2).flatMap { i ->
            listOf(lastMonth, thisMonth).flatMap { m -> listOf(alert(m, i, payee = "Zerodha Coin", umrn = "HDFC0000000099990000"), alert(m, 10 + i, payee = "Zerodha Coin")) }
        }
        val subs = Subscriptions.find(other + second.take(4).let { it }, now, zone)
        assertEquals(setOf(bajaj, "Zerodha Coin"), subs.map { it.payee }.toSet())
        assertEquals(5, subs.count { it.payee == bajaj })
    }

    @Test fun aSixthSipLeavesTheFirstFiveKeys() {
        val before = Subscriptions.find(sips(lastMonth) + sips(thisMonth), now, zone).map { it.key }.toSet()
        val withSixth = Subscriptions.find(sips(lastMonth) + sips(thisMonth, 5) + alert(thisMonth, 5), now, zone)
        assertTrue(withSixth.map { it.key }.toSet().containsAll(before))
        assertEquals(5, withSixth.size)
        val sixthBothMonths = Subscriptions.find(sips(lastMonth, 6) + sips(thisMonth, 6), now, zone)
        assertTrue(sixthBothMonths.map { it.key }.toSet().containsAll(before))
        assertEquals(6, sixthBothMonths.size)
    }

    @Test fun cancellingOneEntryOnlyAffectsThatEntry() {
        val messages = sips(lastMonth) + sips(thisMonth)
        val subs = Subscriptions.find(messages, now, zone)
        val second = subs.first { it.ordinal == 2 }
        val overview = SubscriptionOverview.build(subs, emptyMap(), mapOf(second.key to today), today, messages, zone)
        assertEquals(4, overview.activeCount)
        assertEquals(1, overview.count(SubscriptionState.CANCELLED))
        assertEquals(BigDecimal(96000), overview.yearlyTotal)
        assertEquals(second.key, overview.lines.first { it.state == SubscriptionState.CANCELLED }.key)
    }

    @Test fun distinctMandatesAreKeyedByTheirReference() {
        val ids = listOf("HDFC0000000024040001", "HDFC0000000024040002", "HDFC0000000024040003")
        val messages = listOf(lastMonth, thisMonth).flatMap { m -> ids.mapIndexed { i, id -> alert(m, i, umrn = id) } }
        val subs = Subscriptions.find(messages, now, zone)
        assertEquals(3, subs.size)
        assertEquals(setOf("UMRN …0001", "UMRN …0002", "UMRN …0003"), subs.map { it.reference }.toSet())
        assertTrue(subs.all { "|r" in it.key })
        // The same mandates in another order of arrival keep their keys.
        val shuffled = Subscriptions.find(messages.reversed(), now, zone)
        assertEquals(subs.map { it.key }.toSet(), shuffled.map { it.key }.toSet())
    }

    @Test fun equalMandateIdsFallBackToTheIndex() {
        val subs = Subscriptions.find(sips(lastMonth) + sips(thisMonth), now, zone)
        assertTrue(subs.all { it.reference.isEmpty() && "|2000#" in it.key })
    }

    @Test fun aChargeAfterCancellingOneOfFiveIsOnlyAskedAboutWhenItIsOneTooMany() {
        val messages = sips(lastMonth) + sips(thisMonth)
        val subs = Subscriptions.find(messages, now, zone)
        val cancelled = mapOf(subs.first { it.ordinal == 2 }.key to LocalDate.of(2026, 10, 12))
        val nextMonth = LocalDate.of(2026, 10, 20)
        // Four after the cancelling is what is expected; a fifth is not.
        val four = messages + (0 until 4).map { alert(nextMonth, it) }
        assertTrue(CancelledCharges.find(four, cancelled, zone, now).isEmpty())
        val five = messages + (0 until 5).map { alert(nextMonth, it) }
        assertEquals(1, CancelledCharges.find(five, cancelled, zone, now).size)
    }
}
