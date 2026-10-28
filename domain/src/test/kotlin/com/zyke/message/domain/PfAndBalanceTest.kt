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

