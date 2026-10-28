package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.pf.ProvidentFunds
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PfAndBalanceTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneId.of("UTC")
    private fun sms(id: Long, body: String, sender: String = "AD-EPFOHO") = classifier.classify(SmsMessage(id, sender, body, id * 86_400_000L, true))

    @Test
    fun federalBalanceMessage_isABalanceNotice_andReadAsABalance() {
        val m = sms(1, "Your available balance for a/c no(s) SBA9999 is INR 9,999 .For detailed statement download FedMobile https://x.co - Federal Bank", "AD-FEDBNK")
        assertEquals(NoticeKind.BALANCE_UPDATE, (m.content as MessageContent.Notice).kind)
        val balance = BalanceReader.latest(listOf(m), zone).single()
        assertEquals("9999", balance.account)
        assertEquals(0, balance.amount.compareTo(java.math.BigDecimal("9999")))
    }

    @Test
    fun passbookMessage_isPfUpdate_withAmountAndBalance() {
        val m = sms(2, "Dear Member, your EPF passbook has been updated with Rs. 3,500 for wage month Sep-2026. Your total balance is Rs. 1,25,000.")
        assertEquals(NoticeKind.PF_UPDATE, (m.content as MessageContent.Notice).kind)
        val pf = ProvidentFunds.find(listOf(m), zone)
        assertNotNull(pf)
        assertEquals(0, pf!!.balance!!.compareTo(java.math.BigDecimal("125000")))
        assertEquals(0, pf.latest.added!!.compareTo(java.math.BigDecimal("3500")))
    }

    @Test
    fun passbookMessage_withoutBalance_showsTheAmountOnly() {
        val m = sms(3, "Your PF passbook has been updated with Rs.2,100.")
        val pf = ProvidentFunds.find(listOf(m), zone)!!
        assertTrue(pf.balance == null)
        assertEquals(0, pf.latest.added!!.compareTo(java.math.BigDecimal("2100")))
    }

    @Test
    fun uanPassbookBalanceMessage_isRead() {
        val m = sms(4, "Dear XXXXXXXX4705, your passbook balance against TBTAM******8804 is Rs. 9,99,999/-. Contribution of Rs. 23,400/- for due month Jun-26 has been received.")
        assertEquals(NoticeKind.PF_UPDATE, (m.content as MessageContent.Notice).kind)
        val pf = ProvidentFunds.find(listOf(m), zone)!!
        assertEquals(0, pf.balance!!.compareTo(java.math.BigDecimal("999999")))
        assertEquals(0, pf.latest.added!!.compareTo(java.math.BigDecimal("23400")))
    }
}

class FederalBalanceAccountsTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun twoAccountsInOneMessage_areBothReadAndListed() {
        val m = MessageClassifier().classify(
            SmsMessage(1, "AD-FEDBNK", "Your available balance for a/c no(s) SBA6438 is INR 0,SBA7198 is INR 23 .For detailed statement download FedMobile https://fedmobile.federalbank.co.in/download-fedmobile/ - Federal Bank", 1_000_000_000L, true)
        )
        val balances = BalanceReader.latest(listOf(m), zone).associateBy { it.account }
        assertEquals(0, balances.getValue("6438").amount.compareTo(java.math.BigDecimal.ZERO))
        assertEquals(0, balances.getValue("7198").amount.compareTo(java.math.BigDecimal("23")))

        val overview = com.zyke.message.domain.accounts.Accounts.build(listOf(m), emptyList(), java.time.Instant.ofEpochMilli(2_000_000_000L), zone)
        assertEquals(setOf("6438", "7198"), overview.accounts.map { it.last4 }.toSet())
    }
}

class PfAccountTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun passbookMemberId_becomesAFundAccountWithItsBalance() {
        val m = MessageClassifier().classify(
            SmsMessage(1, "AD-EPFOHO", "Dear XXXXXXXX4705, your passbook balance against TBTAM******8804 is Rs. 9,99,999/-. Contribution of Rs. 23,400/- for due month Jun-26 has been received.", 1_000_000_000L, true)
        )
        val overview = com.zyke.message.domain.accounts.Accounts.build(listOf(m), emptyList(), java.time.Instant.ofEpochMilli(2_000_000_000L), zone)
        val fund = overview.accounts.single()
        assertEquals("8804", fund.last4)
        assertTrue(fund.isProvidentFund)
        assertEquals(0, fund.balance!!.compareTo(java.math.BigDecimal("999999")))
        assertTrue(overview.totalBalance == null)
    }

    @Test
    fun anAccountTheyAddedIsListedWithoutMessages() {
        val card = com.zyke.message.domain.model.UserCard("u", com.zyke.message.domain.model.CardType.ACCOUNT, "6438", "", null, "Federal Bank")
        val overview = com.zyke.message.domain.accounts.Accounts.build(emptyList(), listOf(card), java.time.Instant.ofEpochMilli(2_000_000_000L), zone)
        assertEquals("6438", overview.accounts.single().last4)
    }
}


class StatedBalanceTest {
    private val zone = ZoneId.of("UTC")
    private val c = MessageClassifier()

    @Test
    fun aPlainBalanceMessage_isReadAndTheNewestWins() {
        val old = c.classify(SmsMessage(1, "AD-FEDBNK", "Rs 500.00 debited from your Federal Bank A/c XX6438 on 20-10-26. Avl Bal Rs 1,000.00", 1_000L * 86_400_000L, true))
        val today = c.classify(SmsMessage(2, "AD-FEDBNK", "Dear Customer, the balance in your Federal Bank A/c XX6438 is Rs. 12,345.67 as on 28-10-2026.", 1_010L * 86_400_000L, true))
        val latest = BalanceReader.latest(listOf(old, today), zone).single()
        assertEquals(0, latest.amount.compareTo(java.math.BigDecimal("12345.67")))
    }

    @Test
    fun aMarkedFundAccount_takesThePassbookBalance() {
        val passbook = c.classify(SmsMessage(1, "AD-EPFOHO", "Dear XXXXXXXX4705, your passbook balance against TBTAM******8804 is Rs. 9,99,999/-. Contribution of Rs. 23,400/- for due month Jun-26 has been received.", 1_000_000_000L, true))
        val card = com.zyke.message.domain.model.UserCard("u", com.zyke.message.domain.model.CardType.ACCOUNT, "1111", "", null, "Some Bank")
        val overview = com.zyke.message.domain.accounts.Accounts.build(
            listOf(passbook), listOf(card), java.time.Instant.ofEpochMilli(2_000_000_000L), zone,
            providentFunds = setOf("bank_account-1111")
        )
        val fund = overview.accounts.single()
        assertEquals("1111", fund.last4)
        assertEquals(0, fund.balance!!.compareTo(java.math.BigDecimal("999999")))
    }
}

class EstimatedBalanceTest {
    private val zone = ZoneId.of("UTC")
    private val c = MessageClassifier()
    private val day = 86_400_000L

    @Test
    fun laterMovements_adjustTheStatedBalance_andOlderOnesNever() {
        val salary = c.classify(SmsMessage(1, "AD-FEDBNK", "Rs 50000.00 credited to your Federal Bank A/c XX6438 on 30-09-26 by NEFT ACME PVT LTD", 1_000L * day, true))
        val stated = c.classify(SmsMessage(2, "AD-FEDBNK", "Your available balance for a/c no(s) SBA6438 is INR 10,000 .For detailed statement download FedMobile https://x.co - Federal Bank", 1_020L * day, true))
        val spend = c.classify(SmsMessage(3, "AD-FEDBNK", "Rs 600.00 debited from your Federal Bank A/c XX6438 on 28-10-26 to VPA cafe@fbl. Ref 99", 1_021L * day, true))

        val o = com.zyke.message.domain.accounts.Accounts.build(listOf(salary, stated, spend), emptyList(), java.time.Instant.ofEpochMilli(1_022L * day), zone)
        val account = o.accounts.single { it.last4 == "6438" }
        assertEquals(0, account.balance!!.compareTo(java.math.BigDecimal("9400")))
        assertEquals(0, o.totalBalance!!.compareTo(java.math.BigDecimal("9400")))

        // Filing the old credit as salary changes nothing about the balance.
        val filed = salary.copy(category = com.zyke.message.domain.model.MessageCategory.SALARY, categoryOverridden = true)
        val after = com.zyke.message.domain.accounts.Accounts.build(listOf(filed, stated, spend), emptyList(), java.time.Instant.ofEpochMilli(1_022L * day), zone)
        assertEquals(0, after.totalBalance!!.compareTo(o.totalBalance))
    }
}

class CardUnbilledTest {
    private val zone = ZoneId.of("UTC")
    private val c = MessageClassifier()
    private fun at(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun spendAfterTheStatementDay_isUnbilled_andTheBillIsKept() {
        val bill = c.classify(SmsMessage(1, "AD-HDFCBK", "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:05-11-2026 pay https://hdfcbk.io/1234", at(2026, 10, 12), true))
        val before = c.classify(SmsMessage(2, "AD-HDFCBK", "Rs.900.00 spent on HDFC Bank Credit Card XX1234 at SWIGGY on 10-10-26", at(2026, 10, 10), true))
        val after1 = c.classify(SmsMessage(3, "AD-HDFCBK", "Rs.400.00 spent on HDFC Bank Credit Card XX1234 at SWIGGY on 15-10-26", at(2026, 10, 15), true))
        val after2 = c.classify(SmsMessage(4, "AD-HDFCBK", "Rs.600.00 spent on HDFC Bank Credit Card XX1234 at AMAZON on 20-10-26", at(2026, 10, 20), true))
        val card = com.zyke.message.domain.model.UserCard("c", com.zyke.message.domain.model.CardType.CREDIT, "1234", "", 12, "HDFC Bank")

        val o = com.zyke.message.domain.accounts.Accounts.build(listOf(bill, before, after1, after2), listOf(card), java.time.Instant.ofEpochMilli(at(2026, 10, 25)), zone)
        val a = o.accounts.single { it.last4 == "1234" }
        assertEquals(java.time.LocalDate.of(2026, 10, 12), a.statementOn)
        assertEquals(0, a.unbilled!!.compareTo(java.math.BigDecimal("1000")))
        assertEquals(0, a.dueAmount!!.compareTo(java.math.BigDecimal("12345")))
    }
}

class BillPaidTest {
    private val zone = ZoneId.of("UTC")
    private val c = MessageClassifier()
    private fun at(m: Int, d: Int, h: Int = 10) = java.time.LocalDate.of(2026, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private val bill = c.classify(SmsMessage(1, "AD-HDFCBK", "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:05-11-2026 pay https://hdfcbk.io/1234", at(10, 12), true))

    @Test
    fun aPaymentMessage_marksTheBillPaid_forTheDayItHappens_thenItGoes() {
        val paid = c.classify(SmsMessage(2, "AD-HDFCBK", "Payment of Rs.12,345.00 received towards your HDFC Bank Credit Card XX1234 on 28-10-2026. Thank you.", at(10, 28), true))
        val messages = listOf(paid, bill)

        val today = com.zyke.message.domain.home.UpcomingBuilder.build(messages, java.time.LocalDate.of(2026, 10, 28), includePaidToday = true, zone = zone)
        assertEquals(java.time.LocalDate.of(2026, 10, 28), today.single().paidOn)
        assertTrue(today.single().paymentLink == null)

        val tomorrow = com.zyke.message.domain.home.UpcomingBuilder.build(messages, java.time.LocalDate.of(2026, 10, 29), includePaidToday = true, zone = zone)
        assertTrue(tomorrow.isEmpty())
    }

    @Test
    fun anUnpaidBill_staysUp_andSpendingOnTheCardDoesNotPayIt() {
        val spend = c.classify(SmsMessage(3, "AD-HDFCBK", "Rs.12345.00 spent on HDFC Bank Credit Card XX1234 at AMAZON on 20-10-26", at(10, 20), true))
        val up = com.zyke.message.domain.home.UpcomingBuilder.build(listOf(spend, bill), java.time.LocalDate.of(2026, 10, 28), includePaidToday = true, zone = zone)
        assertEquals(null, up.single().paidOn)
    }

    @Test
    fun anAnnouncedAutoDebit_isNotAPaymentYet() {
        val announced = c.classify(SmsMessage(4, "AD-HDFCBK", "Your HDFC Bank Credit Card XX1234 bill of Rs.12,345.00 will be auto debited on 05-11-2026.", at(10, 20), true))
        val up = com.zyke.message.domain.home.UpcomingBuilder.build(listOf(announced, bill), java.time.LocalDate.of(2026, 10, 28), includePaidToday = true, zone = zone)
        assertTrue(up.any { it.paidOn == null && it.kind == com.zyke.message.domain.home.UpcomingKind.CARD_BILL })
    }
}

class AutoDebitPaidTest {
    private val zone = ZoneId.of("UTC")
    private val c = MessageClassifier()
    private fun at(d: Int, h: Int) = java.time.LocalDate.of(2026, 10, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun anAnnouncedAutoDebit_isPaidOnceTheDebitArrives() {
        val announced = c.classify(SmsMessage(1, "AD-HDFCBK", "Your SIP of Rs.5,000.00 will be debited from A/c XX5765 on 28-10-2026 towards ACME FUND.", at(25, 9), true))
        val scheduled = announced.content as? MessageContent.ScheduledPayment
        if (scheduled != null && scheduled.date != null) {
            val debit = c.classify(SmsMessage(2, "AD-HDFCBK", "Rs.5000.00 debited from A/c XX5765 on 28-10-26 to ACME FUND via NACH", at(28, 8), true))
            val up = com.zyke.message.domain.home.UpcomingBuilder.build(listOf(debit, announced), java.time.LocalDate.of(2026, 10, 28), includePaidToday = true, zone = zone)
            assertEquals(java.time.LocalDate.of(2026, 10, 28), up.single().paidOn)
            assertTrue(com.zyke.message.domain.home.UpcomingBuilder.build(listOf(debit, announced), java.time.LocalDate.of(2026, 10, 29), includePaidToday = true, zone = zone).isEmpty())
        }
    }

    @Test
    fun aBillPaymentThatNamesNoCardDigits_stillCountsWhenTheBankAndTheBillAreNamed() {
        val bill = c.classify(SmsMessage(1, "AD-HDFCBK", "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:28-10-2026 pay https://hdfcbk.io/1234", at(12, 10), true))
        val debited = c.classify(SmsMessage(2, "AD-HDFCBK", "Rs.12345.00 debited from your HDFC Bank A/c XX5765 towards credit card bill payment on 28-10-26", at(28, 9), true))
        val up = com.zyke.message.domain.home.UpcomingBuilder.build(listOf(debited, bill), java.time.LocalDate.of(2026, 10, 28), includePaidToday = true, zone = zone)
        assertEquals(java.time.LocalDate.of(2026, 10, 28), up.single().paidOn)
    }
}
