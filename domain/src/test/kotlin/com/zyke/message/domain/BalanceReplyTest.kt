package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.pf.ProvidentFunds
import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAMPLE reply bodies, written from how banks word their balance replies (account digits masked, amounts invented). They are NOT
 * captured from a real phone: the real wording must be confirmed on a device and any difference added here.
 */
class BalanceReplyTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneId.of("UTC")
    private fun classify(body: String, sender: String, id: Long = 1, date: Long = 1_700_000_000_000L): ClassifiedMessage =
        classifier.classify(SmsMessage(id, sender, body, date, true))

    private fun expect(body: String, sender: String, account: String, amount: String) {
        val message = classify(body, sender)
        assertEquals("a balance reply is a balance notice, not a payment: $body", NoticeKind.BALANCE_UPDATE, (message.content as? MessageContent.Notice)?.kind)
        val found = BalanceReader.latest(listOf(message), zone)
        assertEquals("not read: $body", 1, found.size)
        assertEquals(account, found.single().account)
        assertEquals(0, found.single().amount.compareTo(BigDecimal(amount)))
    }

    // ---- HDFC Bank (sender VM-HDFCBK / AX-HDFCBK-S, not the number that was texted)
    @Test fun hdfcAvailableBalanceInAccount() =
        expect("Available balance in A/c XX1234 is Rs. 12,345.67 as on 08-NOV-26. -HDFC Bank", "VM-HDFCBK", "1234", "12345.67")

    @Test fun hdfcAvlBalFollowedByTheAccount() =
        expect("Avl Bal Rs.12,345.67 in A/c XX1234 on 08-NOV-26. -HDFC Bank", "AX-HDFCBK-S", "1234", "12345.67")

    @Test fun hdfcAvlBalForTheAccount() =
        expect("Avl Bal Rs 12,345.67 for A/c XX1234. -HDFC Bank", "VM-HDFCBK", "1234", "12345.67")

    @Test fun hdfcDearCustomerWithInr() =
        expect("Dear Customer, Available Balance in your A/c XX1234 is INR 12,345.67 as on 08-NOV-2026 -HDFC Bank", "VM-HDFCBK", "1234", "12345.67")

    @Test fun hdfcAccountEndingDigits() =
        expect("HDFC Bank: Avl. Bal in A/c ending 1234 is Rs. 12,345.67", "VM-HDFCBK", "1234", "12345.67")

    // ---- Federal Bank
    @Test fun federalBalInAccount() =
        expect("Bal in A/C XX1234 is Rs 5,000.00 -Federal Bank", "VM-FEDBNK", "1234", "5000")

    @Test fun federalBalWithCr() =
        expect("Bal in A/C XXXXXX1234 is Rs.5000.00 Cr as on 08-11-26. -Federal Bank", "VM-FEDBNK", "1234", "5000")

    @Test fun federalSavingsPrefixOnTheDigits() =
        expect("Dear Customer, Bal in A/c SB1234 is Rs.5,000.00 -Federal Bank", "VM-FEDBNK", "1234", "5000")

    @Test fun federalAvailableBalanceForAccounts() =
        expect("Your available balance for a/c no(s) SB1234 is INR 5,000.00 -Federal Bank", "AD-FEDBNK", "1234", "5000")

    @Test fun aReplyIsNeverAPayment() {
        val message = classify("Bal in A/C XX1234 is Rs 5,000.00 -Federal Bank", "VM-FEDBNK")
        assertTrue(message.content !is MessageContent.Transaction)
    }

    @Test fun aDebitAlertThatEndsInAvlBalIsStillAPayment() {
        val message = classify("Rs.500.00 debited from A/c XX1234 on 08-11-26 to SWIGGY via UPI. Avl Bal Rs 4,000.00", "AX-HDFCBK")
        assertTrue(message.content is MessageContent.Transaction)
    }

    @Test fun aWalletOrCardBalanceIsNotTakenForAnAccountReply() {
        val wallet = classify("Your wallet balance is Rs 500.00", "VM-PAYTMB")
        assertTrue((wallet.content as? MessageContent.Notice)?.kind != NoticeKind.BALANCE_UPDATE)
        val card = classify("Avl limit on your Credit Card XX1234 is Rs 50,000.00", "VM-HDFCBK")
        assertTrue((card.content as? MessageContent.Notice)?.kind != NoticeKind.BALANCE_UPDATE)
    }

    @Test fun aNewerReplyReplacesTheOlderBalance() {
        val older = classify("Bal in A/C XX1234 is Rs 5,000.00 -Federal Bank", "VM-FEDBNK", id = 1, date = 1_700_000_000_000L)
        val newer = classify("Bal in A/C XX1234 is Rs 4,200.00 -Federal Bank", "VM-FEDBNK", id = 2, date = 1_700_000_900_000L)
        val latest = BalanceReader.latest(listOf(older, newer), zone).single()
        assertEquals(0, latest.amount.compareTo(BigDecimal("4200")))
        assertEquals(1_700_000_900_000L, latest.at)
    }

    // ---- EPFO (sender e.g. EPFOHO): recognised as a provident fund balance
    private fun pf(body: String) = ProvidentFunds.find(listOf(classify(body, "AD-EPFOHO")), zone)

    @Test fun epfoReplyWithBalanceAndLastContribution() {
        val message = classify("EPFO: Dear member, your PF balance is Rs 1,25,000 and last contribution Rs 3,500 was received for 10/2026", "AD-EPFOHO")
        assertEquals(NoticeKind.PF_UPDATE, (message.content as MessageContent.Notice).kind)
        val fund = pf(message.message.body)!!
        assertEquals(0, fund.balance!!.compareTo(BigDecimal("125000")))
        assertEquals(0, fund.latest.added!!.compareTo(BigDecimal("3500")))
    }

    @Test fun epfoReplyWithDateAndUan() {
        val fund = pf("Dear member, your PF balance as on 08-11-2026 for UAN 1012XXXX1234 is Rs. 1,25,000/-. -EPFO")!!
        assertEquals(0, fund.balance!!.compareTo(BigDecimal("125000")))
    }

    @Test fun epfoReplyMentioningPfBalanceAgainstUan() {
        val fund = pf("Dear Member, your PF balance against UAN 1012XXXX1234 is Rs. 1,25,000/-")!!
        assertNotNull(fund.balance)
    }

    @Test fun anAdvertWithPfBalanceWordsAndNoMemberOrEpfoIsLeftAlone() {
        val message = classify("Check your PF balance now with our app, it is Rs 0 to join", "VM-SHOPAB")
        assertTrue((message.content as? MessageContent.Notice)?.kind != NoticeKind.PF_UPDATE)
    }
}
