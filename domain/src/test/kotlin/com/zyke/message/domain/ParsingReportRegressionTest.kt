package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message shapes that a user's parsing report showed as plain text; each is rebuilt here with made-up numbers. */
class ParsingReportRegressionTest {
    private val classifier = MessageClassifier()

    private fun classify(body: String, sender: String = "AD-HDFCBK") =
        classifier.classify(SmsMessage(1, sender, body, 0L, true)).content

    private fun scheduled(body: String): MessageContent.ScheduledPayment =
        classify(body) as? MessageContent.ScheduledPayment ?: error("not a scheduled payment")

    private fun transaction(body: String): MessageContent.Transaction =
        classify(body) as? MessageContent.Transaction ?: error("not a transaction")

    private fun bill(body: String): MessageContent.CardBill =
        classify(body) as? MessageContent.CardBill ?: error("not a card bill")

    @Test
    fun cancelledMandate_withAndWithoutServiceName() {
        val named = scheduled("Update:\nRs. 199.00 UPI mandate to Google Play has been cancelled from HDFC Bank A/c x1234.\nUMN: abc@okhdfcb")
        assertEquals(ScheduledKind.MANDATE_CANCELLED, named.kind)
        assertEquals("199", named.amount)
        assertEquals("Google Play", named.payee)
        assertEquals("1234", named.accountNumber)

        assertEquals(
            ScheduledKind.MANDATE_CANCELLED,
            scheduled("Update:\nRs. 199.00 UPI mandate to Netflix has been cancelled from HDFC Bank A/c x1234.").kind
        )
    }

    @Test
    fun upiMandateConfirmation_isAutopaySetUp_notSpend() {
        val c = scheduled("UPI-Mandate:\nFor Rs. 1500.00\nTo Netflix\nA/c x1234\nRef 123456789012\nNot you? Call 18002586161/ SMS BLOCK UPI to 7308080808\n-HDFC Bank")
        assertEquals(ScheduledKind.MANDATE_SET, c.kind)
        assertEquals("1500", c.amount)
        assertEquals("Netflix", c.payee)
    }

    @Test
    fun upiMandateRequest_needsApproval() {
        val c = scheduled("UPI-Mandate:\nYou have a UPI request of Rs.2500.00 from Netflix your UPI app to block\nApplication No JPLSDF123456789012\n-HDFC Bank")
        assertEquals(ScheduledKind.MANDATE_REQUEST, c.kind)
        assertEquals("2500", c.amount)
        assertEquals("Netflix", c.payee)
    }

    @Test
    fun atmWithdrawal_isMoneyOut() {
        val c = transaction("UPI ATM Withdrawal:Rs.500.0\nFrom:HDFC Bank A/c 1234\nAt:SBI ATM CASH ATM\nUPI RRN:123456789012 on 25/10/25\nNot you? Call 18002586161/SMS BLOCK UPI to 7308080808")
        assertFalse(c.isReceived)
        assertEquals("500", c.amount)
        assertEquals("1234", c.accountNumber)
        assertEquals("ATM cash", c.payee)
    }

    @Test
    fun axisStatement_withDrPrefixedAmounts() {
        val c = bill(
            "Your statement for Axis Bank Credit Card no. XX1234 is generated.\nDue on: 20-11-25\n" +
                "Total amt: INR  Dr. 12345.67\nMin amt due: INR  Dr. 617.28\nPay at axis.bank.in/ccpaynow\n" +
                "To view fees & charges: axis.bank.in/kfs\nTo view/download statement: https://axis.bank.in/s/abc"
        )
        assertEquals(BankName.AXIS, c.bankName)
        assertEquals("12345.67", c.amount)
        assertEquals("617.28", c.minimumDue)
        assertEquals(LocalDate.of(2025, 11, 20), c.dueDate)
        assertEquals("1234", c.accountNumber)
        assertEquals("https://axis.bank.in/ccpaynow", c.paymentLink)
    }

    @Test
    fun cardStatement_withTotalPaymentWording() {
        val c = bill("Dear Customer, your statement for Jane Card **********12345 has been generated. Total payment of Rs.8450.50 is due by 05/11/25. Please refer to your statement for min payment due.")
        assertEquals("8450.50", c.amount)
        assertEquals(LocalDate.of(2025, 11, 5), c.dueDate)
        assertEquals(null, c.minimumDue)
    }

    @Test
    fun rechargeProcessed_isMoneyOut() {
        val c = transaction("Hi, we have processed Rs. 299.0 for your Airtel Mobile 9876543210. The payment will be updated within 10 minutes. Please keep your order ID 1234567890123456789 for future reference. Track your payment status via https://x.in/abc")
        assertFalse(c.isReceived)
        assertEquals("299", c.amount)
        assertTrue(c.payee.startsWith("Airtel"))
    }

    @Test
    fun razorpayDonation_isMoneyOut() {
        val c = transaction("Payment of Rs.1000 towards subscription for Jane On Hunger Foundation successfully made on 26 October 2025 - Razorpay")
        assertFalse(c.isReceived)
        assertEquals("1000", c.amount)
        assertEquals("Hunger Foundation", c.payee)
    }

    @Test
    fun ordinaryBalanceAlert_isStillNotAMovement() {
        assertNotNull(classify("Your a/c balance is Rs. 5000.00 as of today"))
        assertFalse(classify("Your a/c balance is Rs. 5000.00 as of today") is MessageContent.Transaction)
    }

    @Test
    fun sipProcessed_isMoneyOut() {
        val c = transaction("Dear Investor, your SIP in 1234567890 in Mahindra Manulife Value Fund Reg Growth for Rs.5,000.00 is processed and units 120.123 at NAV 41.6000 dated 10-Oct-2026 are allotted on receipt of funds. - Mahindra Manulife MF.")
        assertEquals(BankName.MUTUAL_FUND, c.bankName)
        assertEquals("5000", c.amount)
        assertEquals("Mahindra Manulife Value Fund Reg Growth", c.payee)
    }

    @Test
    fun hdfcAutopaySuccess_isCardSpend() {
        val c = transaction("AutoPay (E-mandate) Success!\nFor Netflix - Conten..\nTxn Amt:INR649.00\nDt:10/10/2026\nVia:HDFC Bank CC 1234\nMandate ID: XMhddGVLF9\nNot You? Call 18002586161")
        assertEquals("649", c.amount)
        assertEquals("1234", c.accountNumber)
        assertEquals("Netflix", c.payee)
    }

    @Test
    fun axisAutopayActivation_isAutopaySetUp() {
        val c = scheduled("AutoPay activation successful.\nTxn amt: INR 189.00\nMerchant: Youtube\nAxis Bank Card no.: XX1234\nMax limit: INR 15000.00\nID: YgSNb9CCqk\nManage AutoPay at https://x.in")
        assertEquals(ScheduledKind.MANDATE_SET, c.kind)
        assertEquals("189", c.amount)
        assertEquals("Youtube", c.payee)
        assertEquals("1234", c.accountNumber)
    }

    @Test
    fun eMandateDeduction_isUpcomingDebit() {
        val c = scheduled("E-Mandate!\nRs.1500.00 will be deducted on 28/10/26, 10:00:00\nFor Jane On Hunger Foundation mandate\nUMN abc@okhdfcbank\nMaintain Balance\n-HDFC Bank")
        assertEquals(ScheduledKind.UPCOMING_DEBIT, c.kind)
        assertEquals("1500", c.amount)
        assertEquals(LocalDate.of(2026, 10, 28), c.date)
    }

    @Test
    fun paymentAlertAndNetBanking_areMoneyOut() {
        val alert = transaction("PAYMENT ALERT! \nINR 2500.00 deducted from HDFC Bank A/C No 1234 towards ACME LOANS UMRN: HDFC1234567890123456")
        assertEquals("2500", alert.amount)
        assertEquals("1234", alert.accountNumber)
        assertEquals("ACME LOANS", alert.payee)

        val net = transaction("Payment Successful! Rs. 12000.50 from A/c **********1234 to Jane Doe via HDFC Bank NetBanking. Not you?Call 18002586161")
        assertEquals("12000.50", net.amount)
        assertEquals("Jane Doe", net.payee)
    }

    @Test
    fun upiLiteAtmPosAndPremium_areMoneyOut() {
        assertEquals("500", transaction("UPI LITE Top-up amounting to Rs.500.00 has been successful.\nRef No 123456789012\n- HDFC Bank").amount)

        val atm = transaction("Rs.2000.00 w/d from A/c XX1234 at ABHTN123 , GN CHETTY ROAD T NGR on 10/10/26 10:00 RRN:123456789012.Not you? SMS BLOCK to 9444012345-IndianBank")
        assertEquals(BankName.INDIAN_BANK, atm.bankName)
        assertEquals("2000", atm.amount)

        val pos = transaction("Razorpay POS Receipt:https://x.in/abc\nAMPLE TECHNOLOGIES\nINR12,500")
        assertEquals("12500", pos.amount)
        assertEquals("AMPLE TECHNOLOGIES", pos.payee)

        assertEquals("9.99", transaction("DEAR CUSTOMER, PNR 1234567890; PA INSURANCE CERTIFICATE/S MAILED; PREMIUM RS. 9.99. FOR JANE & TO JOHN https://x.in").amount)
    }

    @Test
    fun electricityBillAndSipReminder_areUpcoming() {
        val bill = scheduled("Electricity charges of Rs.450 for 120 units for Jane 123456789012 is due on 30/10/2026. Pay at www.tnebnet.org/qwp/qpay?st=123456789012 -TANGEDCO")
        assertEquals("450", bill.amount)
        assertEquals(LocalDate.of(2026, 10, 30), bill.date)

        val sip = scheduled("Dear Investor, Your SIP instalment in Folio No. XXXXXXX1234 for Rs.2000.00 is due for debit from your XXXXXXXXXX1234 A/c on 05/11/2026. Request you to maintain sufficient balance in your account. Regards, Bajaj Finserv Mutual Fund.")
        assertEquals("2000", sip.amount)
        assertEquals(LocalDate.of(2026, 11, 5), sip.date)
    }

    @Test
    fun fastagRecharge_isTopUp() {
        val event = (classify("Hi, Recharge successful: IDFC FIRST Bank FASTag on TN01AB1234 credited with Rs.500.0 on 10/10/2026 10:00. Ref:123456789012345678 Avbl Bal: Rs.620.5") as MessageContent.FastTag).event
        assertEquals("TN01AB1234", event.vehicleNumber)
    }

    private fun notice(body: String): MessageContent.Notice =
        classify(body) as? MessageContent.Notice ?: error("not a notice")

    @Test
    fun informationalMessages_areNoticesNotUnparsed() {
        assertEquals(NoticeKind.AUTOPAY_FAILED, notice("Autopay Failed:\nINR.649.00 on 10/10/2026 via HDFC Bank Card 1234 for Netflix\nID:XSiUpjrhk9\nManage:https://x.in").kind)
        assertEquals(NoticeKind.REWARD_REDEMPTION, notice("Redeem your Axis Bank EDGE Miles to pay for your recent transaction of INR 999.00 on card no. XX1234. Click https://x.in within 48 hours to redeem. TnC.").kind)
        assertEquals(NoticeKind.IPO_ALLOTMENT, notice("Dear ABCDE1234F IPO Jio Platforms Limited: 10 shares allotted at Rs 100.00 for Jane no SDF123. Refer registered email id for details- NSEIL").kind)
        assertEquals(NoticeKind.TAX_DEDUCTED, notice("Total TDS by Acme of PAN ABCDE1234F for Jane ending Jun 26 is Rs 12,345 and cumulative TDS for FY 26-27 is Rs 23,456. View Form 26AS/ATS for details. ITD Team.").kind)
        assertEquals(NoticeKind.BROKER_BALANCE, notice("ZERODHABROKINGLIMITED on 10-10-2026 reported your Fund bal Rs.-100.5 & Securities bal 9.5. This excludes your Bank, DP & PMS bal with the broker-NSE").kind)
        assertEquals("50000.00", notice("Dear Jane, a total of Rs. 50000.00/- is approved against case ID 123456. Kindly contact hospital for further details. Wish you a speedy recovery.Regards FHPL").amount)
        assertEquals(NoticeKind.PAYMENT_REQUEST, notice("To pay INR 1500.00 for your Order with Tracking # EZ123456789012345678 please click https://x.in - Razorpay POS").kind)
        assertEquals(NoticeKind.FASTAG_PASS, notice("Hi, annual pass issued on FASTag against TN01AB1234. Validity: 01/10/2026 to 30/09/2027 or 200 tolls/year. Amt. paid: Rs.3075. - IDFC FIRST Bank").kind)
    }

    @Test
    fun scamStyleMessages_areFlaggedAsPromotional() {
        assertTrue(classifier.classify(SmsMessage(1, "AX-XYZ", "Cust ID: Dear Sir/Mam Inv No.: Salary Rs99999, You can work from home Total Due amount: api.whatsapp.com/send?phone=919999999999 Pay Link Galaxy", 0L, true)).isPromotional)
        assertTrue(classifier.classify(SmsMessage(1, "AX-XYZ", "Dear Customer Your order for Jane on Rs99999 is confirmed. Service List: Simple Working Thanks Details api.whatsapp.com/send?phone=919999999999 VTPL", 0L, true)).isPromotional)
    }

    @Test
    fun round3_moneyMessages() {
        val sip = transaction("Dear Investor, Your SIP under folio XXXXXXX1234 in Bajaj Finserv Multi Cap Fund Regular Plan - Growth for Rs. 3000.00 is processed and 120.123 units at NAV 25.123 dated 10/10/2026 are allotted on receipt of funds. Regards, Bajaj Finserv MF.")
        assertEquals("3000", sip.amount)
        assertEquals("Bajaj Finserv Multi Cap Fund Regular Plan - Growth", sip.payee)

        assertEquals("Airtel recharge", transaction("Recharge of INR 299.00 is successful for your Airtel Mobile on 10-10-2026 10:00, Transaction ID 123456789.Check your balance on Airtel app i.airtel.in/SerAIr .").payee)
        assertEquals("Domino's", transaction("Thanks for choosing Domino's.We will deliver your order hot & fresh at the earliest|Order no. 123 |Amount:Rs. 1,299.50 |Track order@ https://x.in").payee)
        assertEquals("65000", transaction("Dear Prashanth, your flight booking with R123 booking ID: 12345678901234567890 is successfully confirmed by Acme for INR 65000. Please check your email.").amount)

        val bill = scheduled("Dear Jane,\nBill Dated 1st of this month of Rs.1180 is Due on 10-Jul-2026.Kindly Pay by Acme www.skylinknet.in/re?r=p99pm9 Before Due Date to Acme")
        assertEquals("Skylink", bill.payee)
        assertEquals(LocalDate.of(2026, 7, 10), bill.date)
        assertEquals("1180", scheduled("Dear Jane,\nBill Dated 1-JUL of Rs.1180 is Over Due Today. Kindly Pay by Acme www.skylinknet.in/re?r=9xiw9e Immediately").amount)

        val toll = (classify("Dear Customer, txn. of INR 95 using IDFC FIRST Bank FASTag on TN01AB1234 done at Acme LTD on 10/10/2026 10:00. Avbl. Bal.: INR 505.50") as MessageContent.FastTag).event
        assertEquals("TN01AB1234", toll.vehicleNumber)
    }

    @Test
    fun round3_noticesAndSpam() {
        assertEquals(NoticeKind.MANDATE_MODIFIED, notice("AutoPay (E-mandate) Modified!\nFor Netflix\nMax Amt:INR 1500.00\nVia:HDFC Bank CC 1234\nStart:10/10/2026\nEnd:10/10/2027\nSIHub ID:WduBApUHDw\nTnC").kind)
        assertEquals(NoticeKind.FUNDS_UNBLOCKED, notice("Your bank is instructed to unblock Rs. 15000.00 for Jane applno. SDF123 due to non-allotment. Please contact your bank or ignore if already unblocked. -Kfintech").kind)
        assertEquals(NoticeKind.LIMIT_CHANGE, notice("Your overall fund transfer limit has been reduced to INR 50,000. Limit can be increased after 1 Day. - Axis Bank").kind)
        assertEquals(NoticeKind.LIMIT_CHANGE, notice("Welcome to Axis Bank Internet Banking. Current txn limit of INR 25,000 will be upgraded to INR 1,00,000 after 1 Day.").kind)
        assertEquals(NoticeKind.ADVISORY, notice("Dear Customer, kindly do PoS/ECom TXN by Jane debit card once in every 90 days to be eligible for insurance cover of Rs. 2 Lakh by Acme-Indian Bank").kind)
        assertEquals(NoticeKind.DUES_REMINDER, notice("Hello JANE,\n\nThis is to inform you that your Indian Bank,Secured Term Loan, A/C RARCIBCH1234567890 has been assigned to Acme Limited for Acme haven't received the payment toward dues of Rs 12345.67 despite several reminders. If we don't receive it, we will be forced to pursue legal action. -Sent via Credgenics").kind)

        listOf(
            "Trade model gave Rs99,999; proof clear https://x.in",
            "Rs 9,999 profit in 48 hours. Last entries. https://x.in",
            "Dear Sir, \nYour request for Rs.9,99,999 Loan is almost complete. Click on compact.link/pFkVP to view.\nrd",
            "Dear Customer,\nyour Personal Loan request has been approved for Rs.9,99,999. View next steps: compact.link/BTPCa\nRodigi",
            "Airtel Warning: SPAM | 9 trades, Rs 99,999 profit, and his son now goes to private school. https://x.in"
        ).forEach { assertTrue(it, classifier.classify(SmsMessage(1, "AX-XYZ", it, 0L, true)).isPromotional) }
    }

    @Test
    fun tollMessages_carryPlazaAmountAndVehicle() {
        val a = (classify("INR 65 toll paid from HDFC Bank Tag 1234 for vehicle no. KA01AB1234 at Hosur Toll on 12-Jan-25 10:30. Avbl. Bal.: INR435") as MessageContent.FastTag).event as com.zyke.message.domain.model.FastTagEvent.TollPaid
        assertEquals("Hosur", a.tollName)
        assertEquals("65", a.tollAmount)
        assertEquals("KA01AB1234", a.vehicleNumber)

        val b = (classify("Dear Customer, txn. of INR 95 using IDFC FIRST Bank FASTag on TN01AB1234 done at Sholavandan Toll Plaza on 10/10/2026 10:00. Avbl. Bal.: INR 505.50") as MessageContent.FastTag).event as com.zyke.message.domain.model.FastTagEvent.TollPaid
        assertEquals("Sholavandan Toll Plaza", b.tollName)
        assertEquals("95", b.tollAmount)
        assertEquals("505.50", b.balance)
    }

    @Test
    fun rowsAlwaysHaveAName_evenWithoutAPayeeOrWithOnlyAnId() {
        val hash = classify("Sent Rs.13428.00\nFrom HDFC Bank A/C *5765\nTo 5dd063f0f029a9e1e0634dcee10a9f77\nOn 26/10/26\nRef 1")
        assertEquals("UPI payment", (hash as MessageContent.Transaction).let { classifierName("Sent Rs.13428.00\nFrom HDFC Bank A/C *5765\nTo 5dd063f0f029a9e1e0634dcee10a9f77\nOn 26/10/26\nRef 1") })

        val blank = classifierName("Rs.500.00 debited from A/c XX5765 on 26-10-26. Ref 12345")
        assertTrue(blank.isNotBlank())
    }

    private fun classifierName(body: String) = classifier.classify(SmsMessage(1, "AD-HDFCBK", body, 0L, true)).merchant.name
}
