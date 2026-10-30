package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingReportRound5Test {
    private val c = MessageClassifier()
    private val date = java.time.LocalDate.of(2026, 10, 30).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun read(body: String, sender: String = "AD-BANK") = c.classify(SmsMessage(1, sender, body, date, true))
    private fun tx(body: String) = read(body).content as MessageContent.Transaction

    @Test
    fun sbiNetBankingAndAccountAlerts() {
        val sent = tx("Dear Customer, Thx for using SBI INB txn of Rs.25000 frm A/c X1234 to RAVI KUMAR Ref IGAUMMAQX9 on 28Oct26. If not done, fwd this SMS to 9999999999 to block INB or call 9999999999-SBI")
        assertEquals("25000", sent.amount); assertEquals(false, sent.isReceived)
        val credit = tx("Your A/C XXXXX123456 has credit for UPI/DRC/123456789012/12345678/ of Rs 999.99 on 28/10/26. Avl Bal Rs 9,99,999.99.-SBI")
        assertEquals("999.99", credit.amount); assertEquals(true, credit.isReceived)
        val debit = tx("Dear Customer, Your A/C XXXXX123456 has a debit by transfer of Rs 9,999.99 on 28/10/26. Avl Bal Rs 99,999.99. Download YONO - SBI")
        assertEquals("9999.99", debit.amount); assertEquals(false, debit.isReceived)
    }

    @Test
    fun mandatesAreRecognised() {
        listOf(
            "Your UPI-Mandate for Rs.99999.99 is successfully created towards ACME LTD from A/c No: XXXXXX1234. UMN:xx@oksbi. If not you, kindly report on 99999999. -SBI",
            "We have activated Standing Instruction on ICICI Bank Credit Card 1234. Merchant: Anthropic, Maximum Amount: INR 9999.99, Frequency: monthly, Start Date: 01/11/2026, End Date: 01/11/2027, Mandate ID: YirQqiYIyp. To manage your Standing Instructions, visit www.icici.bank.in",
            "Dear Cardholder, your payment e-Mandate set at merchant platform with your credit card ending 1234 has been registered with the following details.\nMerchant: Trading View Inc, Description: Trading View Inc, e-Mandate Limit Amount (INR): 999999.99, Frequency: As Presented, Start date: 01/11/2026, End date:01/11/2027, Si-Hub ID: YiedDJGe99. Please note that you have authorised debit of 99999.99 from your account towards the first Trxn."
        ).forEach {
            val content = read(it).content
            assertTrue(content.toString(), content is MessageContent.ScheduledPayment && content.kind == ScheduledKind.MANDATE_SET)
        }
    }

    @Test
    fun cardBillPaymentsAreCreditsToTheCard() {
        val sbi = tx("Dear SBI Cardholder, payment of Rs. 9999.99 for your SBI Credit Card has been successfully processed. ref no : EKT99ZH99KPA99.")
        assertTrue(sbi.isReceived && sbi.isCardPayment)
        val indus = tx("Dear Customer, thank you for your Payment of INR 99,999.99 towards your IndusInd Bank Credit Card on 28/10/2026 - IndusInd Bank")
        assertTrue(indus.isReceived && indus.isCardPayment)
    }

    @Test
    fun billsAndDueDates() {
        assertTrue(read("Total amt of Rs.9999 is due by 05/11/26 on RBL Bank Credit Card (xxxx99). Pls Pay at https://rbl.example/pay . Delayed payments are reported to credit bureaus.").content is MessageContent.CardBill)
        assertTrue(read("LazyPay statement of Rs. 9999.99 for spends and EMIs between 1st Oct to 25th Oct is due on 30th Oct, 2026. View bill on: https://lazypay.in/x").content is MessageContent.CardBill)
        val airtel = read("Hi Ashwin,\nBill for your One Airtel plan for your registered number 9999999999 dated 28-Oct-2026 has been generated. Details below -\n\nGood news! You have a Rs. 999 coupon waiting for you. Pay your bill on the Airtel Thanks app to redeem it.\n\nCurrent Month Payable Amount: Rs. 9999.99 (Inclusive of tax)\nDue By: 12-Nov-26\n\nBill Summary\nThis month Plan Rental: Rs. 9999.99")
        val scheduled = airtel.content as MessageContent.ScheduledPayment
        assertEquals(java.time.LocalDate.of(2026, 11, 12), scheduled.date)
    }

    @Test
    fun loanReminders_areAnnouncedDebitsWithTheirDay() {
        val today = read("NACH Auto-Debit for HOME loan is presented to your bank account for Rs.9999 due TODAY. Ensure funds in registered bank a/c to avoid bounce charges").content as MessageContent.ScheduledPayment
        assertEquals(java.time.LocalDate.of(2026, 10, 30), today.date)
        val kissht = read("KISSHT NACH Debit of Rs.9999 is due 05-11-2026. Maintain balance to avoid penalty. To stop NACH, PAY BEFORE 9 PM TODAY https://k.example").content as MessageContent.ScheduledPayment
        assertEquals(java.time.LocalDate.of(2026, 11, 5), kissht.date)
        val piramal = read("Dear Customer,\nYour EMI of Rs. 9999.9 for loan account no. PSBPL9999999 will be due on 2026-11-05 10:00:00. Please ensure your account has sufficient balance to avoid any additional charges.\nThanks,\nPiramal Finance").content as MessageContent.ScheduledPayment
        assertEquals(java.time.LocalDate.of(2026, 11, 5), piramal.date)
        val monthly = read("Dear Customer,\n\nGentle Reminder!\nMonthly instalment of Rs.9999 for your Loan Account No.PSBXXX999 is due on 05-11-2026. \n\nPlease maintain a sufficient balance.").content as MessageContent.ScheduledPayment
        assertEquals(java.time.LocalDate.of(2026, 11, 5), monthly.date)
    }

    @Test
    fun feesAndSmallPayments() {
        assertEquals("99.99", tx("Kotak FASTag:Rs.99.99 paid for parking at MALL by 28-10-2026@10:30 for DL99CC9999.Bal.999.99.Visit https://x.y for disputes.").amount)
        assertEquals("999.9", tx("Dear ASHWIN BALAJI S, This is confirmation receipt for your online payment of Rs 999.9, against Order no. 9-999999999999 for consumer no. 9999999999.\nINDANE").amount)
        assertEquals("999.99", tx("Low Balance Alert!\nRs.999.99 +GST charged for low balance in your HDFC Bank A/c X1234. Balance as of SEP-26 Rs.9999.99\nMaintain Rs.99999 to avoid charges").amount)
        assertEquals("9999.99", tx("Dear Customer - Thanks for your payment of Rs. 9999.99 against your LazyPay statement. Explore more offers today on https://x.y").amount)
    }

    @Test
    fun informationIsNotReportedAsUnderstoodMoney() {
        fun kind(body: String) = (read(body).content as? MessageContent.Notice)?.kind
        assertEquals(NoticeKind.TRADE_CONFIRMATION, kind("BSE Trade Confirmation Client Code YOOW99999Q - Broker 9999 - EQ  Value Rs 9.99 - FNO Value Rs 999999.99 - Currency Value Rs. 9.99 - Dated 28-10-2026"))
        assertEquals(NoticeKind.TRADE_CONFIRMATION, kind("Dear BYWXXXXX9M,Your traded value for 28-OCT-26 FO Rs 99999 Check your registered email. For details contact broker -National Stock Exchange."))
        assertEquals(NoticeKind.PAYMENT_DECLINED, kind("Trxn. of Rs.9,999.99 at AMAZON on 28-10-26 with your credit card ending 1234 was declined as International Online usage is not active on your card. To activate, visit 'Manage Card Usage' section on the SBI Card App/website or click: https://x.y - SBI Card"))
        assertEquals(NoticeKind.PAYMENT_DECLINED, kind("Transaction of INR 9,999.99 at OPENAI *CHATGPT on ICICI Bank Credit Card XX9999 was declined due to insufficient credit limit. Available Credit Limit is INR 999.99"))
        assertNotNull(kind("Alert! HDFC Bank A/c XXXX9999 Avg. bal. maintained as on 28-OCT-26: Rs 9999.99 Avoid non-maintenance charges by maintaining Avg. bal. of Rs 99999 in OCT-2026"))
    }
}
