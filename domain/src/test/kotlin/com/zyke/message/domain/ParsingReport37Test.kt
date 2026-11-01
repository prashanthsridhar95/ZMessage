package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.ledger.CountedReason
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NetworkCarrier
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 37 shapes a real parsing report listed as not understood, rebuilt with made-up numbers. */
class ParsingReport37Test {
    private val classifier = MessageClassifier()
    private val aug12 = LocalDate.of(2026, 8, 12).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun classify(body: String, sender: String, id: Long = 1, date: Long = aug12): ClassifiedMessage =
        classifier.classify(SmsMessage(id, sender, body, date, true))

    private fun tx(body: String, sender: String) =
        classify(body, sender).content as? MessageContent.Transaction ?: error("not a transaction: ${classify(body, sender).content}")

    private fun notice(body: String, sender: String) =
        classify(body, sender).content as? MessageContent.Notice ?: error("not a notice: ${classify(body, sender).content}")

    private fun life(body: String, sender: String) =
        classify(body, sender).content as? MessageContent.Life ?: error("not life: ${classify(body, sender).content}")

    private fun understood(sender: String, vararg bodies: String) {
        val messages = bodies.mapIndexed { i, b -> classify(b, sender, id = i + 1L) }
        val report = DiagnosticsBuilder.build(messages)
        assertEquals(report.samples.toString(), 0, report.unrecognised)
    }

    // ---- A. KVB

    private val kvbBalance = "KVB ALERT * INR 1,24,356.78 is the Balance in a/c ****4521 as of 14-AUG-2026 10:32:15 * Download KVB-DLite mobile app from https://kvb.co/dl"

    @Test
    fun kvbBalance_isABalanceUpdateAndFeedsTheBalanceReader() {
        val m = classify(kvbBalance, "VM-KVBANK")
        val n = m.content as MessageContent.Notice
        assertEquals(NoticeKind.BALANCE_UPDATE, n.kind)
        assertFalse(m.isPromotional)
        val b = BalanceReader.latest(listOf(m), ZoneOffset.UTC).single()
        assertEquals("4521", b.account)
        assertEquals(BigDecimal("124356.78"), b.amount)
        assertEquals(LocalDate.of(2026, 8, 14), b.asOf)
        assertEquals("Karur Vysya Bank", b.bank)
        assertEquals(LocalDate.of(2026, 8, 14).atTime(10, 32, 15).toInstant(ZoneOffset.UTC).toEpochMilli(), b.at)
        understood("VM-KVBANK", kvbBalance)
    }

    @Test
    fun kvbBalance_variantsOfMonthAndGrouping() {
        listOf("OCT" to 10, "JUL" to 7, "JUN" to 6, "APR" to 4).forEach { (mon, num) ->
            val m = classify("KVB ALERT * INR 99,999.99 is the Balance in a/c ****0042 as of 03-$mon-2026 09:05:00 * Download KVB-DLite mobile app from https://kvb.co/dl", "AD-KVBANK")
            val b = BalanceReader.latest(listOf(m), ZoneOffset.UTC).single()
            assertEquals(num, b.asOf.monthValue)
            assertEquals(BigDecimal("99999.99"), b.amount)
        }
    }

    @Test
    fun kvbNeftInward_isReceivedWithBalance() {
        val body = "NEFT Inward to A/c No:XX4521,INR:4,56,789.00,Ref No:AXNH252100123456,Rem:SHRIRAM FINANCE LTD ERSTWHILE SHRIRAM TRANSPORT FINANCE COMPANY LTD.,Avl Bal INR 5,12,345.67 -Pending Verification -KVB"
        val m = classify(body, "VM-KVBANK")
        val t = m.content as MessageContent.Transaction
        assertTrue(t.isReceived)
        assertEquals("456789", t.amount)
        assertEquals("Shriram Finance", t.payee)
        assertEquals("4521", t.accountNumber)
        assertEquals(BankName.KVB, t.bankName)
        val b = BalanceReader.latest(listOf(m), ZoneOffset.UTC).single()
        assertEquals(BigDecimal("512345.67"), b.amount)
        understood("VM-KVBANK", body)
    }

    @Test
    fun kvbNeftOutwardAndShortRemark() {
        val t = tx("IMPS Outward to A/c No:XX4521,INR:2,500.00,Ref No:IMP123456,Rem:RAMESH KUMAR,Avl Bal INR 9,500.00 -KVB", "VM-KVBANK")
        assertFalse(t.isReceived)
        assertEquals("Ramesh Kumar", t.payee)
        assertEquals("2500", t.amount)
    }

    @Test
    fun kvbTermDepositClosed_isANoticeNotMoney() {
        val body = "Dear Customer, Your term deposit ***1234/1 for amount INR 50000 has been closed on 12-JAN-26. This is for your information-KVB"
        val m = classify(body, "VM-KVBANK")
        val n = m.content as MessageContent.Notice
        assertEquals(NoticeKind.DEPOSIT_CLOSED, n.kind)
        assertEquals("50000", n.amount)
        assertEquals(MessageCategory.SAVINGS, m.category)
        assertTrue(CountedReason.explain(m).startsWith("Not counted"))
        understood("VM-KVBANK", body)
    }

    @Test
    fun kvbImpsOtps_haveTheEightDigitCodeAndTheAmount() {
        listOf("IMPS", "QUICK IMPS").forEach { kind ->
            val body = "<#> The OTP is 48213679 for Rs.15,000.00 TXN to RAMESH KUMAR, Txn Ref: 523412345678 via DLite MB-$kind. Valid for 5 mins and Keep it confidential -KVB +vcyaxUWFgo"
            val m = classify(body, "VM-KVBANK")
            val otp = m.content as MessageContent.Otp
            assertEquals("48213679", otp.code)
            assertEquals("15000", otp.paymentAmount)
            assertNull(otp.cardNumber)
            assertEquals(MessageCategory.OTP, m.category)
        }
    }

    @Test
    fun ordinaryOtpsAreUnchanged() {
        val six = classify("123456 is your OTP for login. Do not share it with anyone.", "AX-HDFCBK").content as MessageContent.Otp
        assertEquals("123456", six.code)
        assertNull(six.paymentAmount)
        val card = classify("OTP is 654321 for INR 1,500.00 on your card ending 4321 at AMAZON. Valid for 10 mins", "AX-HDFCBK").content as MessageContent.Otp
        assertEquals("654321", card.code)
        assertEquals("4321", card.cardNumber)
        // a reference number is never the code
        assertEquals("778899", (classify("Your OTP for payment of Rs.500 is 778899. Ref 123456789012", "AX-ICICIB").content as MessageContent.Otp).code)
    }

    @Test
    fun kvbRealTransactionsStayTransactions() {
        val debit = tx("Rs.1,200.00 debited from A/c XX4521 on 12-08-26 to ZOMATO via UPI. Avl Bal INR 8,000.00 -KVB", "VM-KVBANK")
        assertFalse(debit.isReceived)
        assertEquals("1200", debit.amount)
        assertEquals(BankName.KVB, debit.bankName)
        val credit = tx("Rs.5,000.00 credited to A/c XX4521 on 12-08-26 by transfer from RAVI. Avl Bal INR 13,000.00 -KVB", "VM-KVBANK")
        assertTrue(credit.isReceived)
    }

    // ---- B. HDFC

    @Test
    fun declinedPayments_areNoticesWithReasonAndNeverSpending() {
        val card = "TXN DECLINED: Rs.1299.00 on 12-10-2026 at AMAZON on HDFC Bank Credit Card 4321. Reason: Online usage disabled. Enable it: https://hdfcbk.io/x/abcd"
        val debit = "Debit Card Txn Declined! For Rs. 500.00 On:Dec 12 2025 8:45PM Reason: Incorrect PIN entered. Visit MyCards https://hdfcbk.io/x/ab to manage"
        val a = notice(card, "VM-HDFCBK")
        assertEquals(NoticeKind.PAYMENT_DECLINED, a.kind)
        assertEquals("Online usage disabled", a.detail)
        assertEquals("1299.00", a.amount)
        val b = notice(debit, "VM-HDFCBK")
        assertEquals(NoticeKind.PAYMENT_DECLINED, b.kind)
        assertEquals("Incorrect PIN entered", b.detail)
        assertEquals("Payment declined", b.kind.label)
        understood("VM-HDFCBK", card, debit)
    }

    @Test
    fun declinedInsideAMerchantNameIsStillADebit() {
        val t = tx("Rs.250.00 debited from A/c XX1234 on 12-08-26 to Declined Coffee Roasters via UPI. Avl Bal Rs 4,000.00", "AX-HDFCBK")
        assertFalse(t.isReceived)
        assertEquals("250", t.amount)
    }

    @Test
    fun upiReversalOnACard_isACreditThatIsNotIncome() {
        val body = "Your UPI transaction of Rs 99.00 has been reversed in your HDFC Bank Credit Card due to technical problem (UPI Ref no. 123456789012)"
        val m = classify(body, "VM-HDFCBK")
        val t = m.content as MessageContent.Transaction
        assertTrue(t.isReceived)
        assertTrue(t.isCard)
        assertEquals("99", t.amount)
        assertTrue(t.isCardPayment)
        assertTrue(CountedReason.explain(m).contains("reversal"))
        understood("VM-HDFCBK", body)
    }

    @Test
    fun reversedWordingElsewhereIsNotAReversal() {
        val t = tx("Rs.500.00 debited from A/c XX1234 on 12-08-26 to SWIGGY via UPI. If not done by you it will be reversed. Avl Bal Rs 4,000.00", "AX-HDFCBK")
        assertFalse(t.isReceived)
        assertFalse(t.isCardPayment)
    }

    @Test
    fun upiLiteEnabled_isAnInternalTopUp() {
        val body = "UPI LITE is successfully enabled for A/C no **1234 with Top-Up of Rs.200.00 on 12-10-25 Ref No 512345678901. - HDFC Bank"
        val m = classify(body, "VM-HDFCBK")
        val t = m.content as MessageContent.Transaction
        assertFalse(t.isReceived)
        assertTrue(t.isCardPayment)
        assertEquals("200", t.amount)
        assertEquals("1234", t.accountNumber)
        assertEquals("UPI Lite", t.payee)
        understood("VM-HDFCBK", body)
    }

    // ---- C. Axis

    private fun axis(narration: String) =
        "Debit INR 354.00\nAxis Bank A/c XX4521\n12-06-26 10:05:33\n$narration\nWhatsApp BAL to 7036165000\nNot You? SMS BLOCKALL CustID to 7308080808"

    @Test
    fun axisSchemeNarrations_useTheNameAfterTheTag() {
        val easy = classify(axis("EASY/PRADHAN MANTRI /12345"), "AX-AXISBK")
        val t = easy.content as MessageContent.Transaction
        assertFalse(t.isReceived)
        assertEquals("354", t.amount)
        assertEquals("Pradhan Mantri", t.payee)
        assertEquals("4521", t.accountNumber)
        assertEquals(MessageCategory.INSURANCE, easy.category)
        assertEquals("PMJJBY insurance premium", (classify(axis("PMJJBY"), "AX-AXISBK").content as MessageContent.Transaction).payee)
        assertEquals("PMSBY insurance premium", (classify(axis("PMSBY"), "AX-AXISBK").content as MessageContent.Transaction).payee)
        assertEquals(MessageCategory.SAVINGS, classify(axis("APY"), "AX-AXISBK").category)
        assertEquals("Netflix", (classify(axis("SI/NETFLIX/998877665544"), "AX-AXISBK").content as MessageContent.Transaction).payee)
        assertEquals("Lic Of India", (classify(axis("NACH/LIC OF INDIA/55"), "AX-AXISBK").content as MessageContent.Transaction).payee)
        understood("AX-AXISBK", axis("EASY/PRADHAN MANTRI /12345"))
    }

    @Test
    fun axisUpiNarrationUnchanged() {
        val t = tx(axis("UPI/P2A/123456789012/JOHN DOE/Axis Bank"), "AX-AXISBK")
        assertEquals("JOHN DOE", t.payee)
    }

    // ---- D. Others

    @Test
    fun rentomojoInvoice_isAnUpcomingBill() {
        val body = "Dear customer, your Rentomojo invoice for Aug '26 is generated. Total due: ₹2499 (incl. previous dues). Please pay by 20-Aug-2026 to avoid late fees. Pay now: https://rmj.in/abc Ignore if paid. –Team Rentomojo"
        val s = classify(body, "VM-RENTMJ").content as MessageContent.ScheduledPayment
        assertEquals(ScheduledKind.UPCOMING_DEBIT, s.kind)
        assertEquals("Rentomojo", s.payee)
        assertEquals("2499", s.amount)
        assertEquals(LocalDate.of(2026, 8, 20), s.date)
        understood("VM-RENTMJ", body)
    }

    @Test
    fun orderConfirmations_acceptTheWordingVariants() {
        listOf("order no is 52", "order no. 52", "Order no 52", "order number 52", "order id 52", "Order No: 52").forEach { phrase ->
            val l = life("Thanks for choosing Popeyes! Your $phrase | Amount: Rs. 549.00 | Track your order@ https://pop.eyes/t/ab12", "VM-POPEYE")
            assertEquals(phrase, LifeKind.DELIVERY, l.kind)
            assertEquals(phrase, "52", l.reference)
            assertEquals("549.00", l.amount)
            assertEquals("Popeyes", l.title)
        }
    }

    @Test
    fun orderLookingBankAlertIsStillADebit() {
        val t = tx("Rs. 549.00 debited from A/c XX1234 for order no 52 | Amount: Rs. 549.00 UPI. Avl Bal Rs 2,000.00", "AX-HDFCBK")
        assertFalse(t.isReceived)
    }

    @Test
    fun payuConfirmation_isNotCountedAsADebit() {
        val m = classify("Transaction No. 12345678901 for Rs. 1499.00 done for Arun Kumar has succeeded Team PayU", "VM-PAYU")
        val n = m.content as MessageContent.Notice
        assertEquals(NoticeKind.PAYMENT_CONFIRMATION, n.kind)
        assertEquals("1499.00", n.amount)
        assertEquals("PAYU", m.merchant.name.uppercase())
        assertTrue(CountedReason.explain(m).startsWith("Not counted"))
    }

    @Test
    fun mandateDeclined_isANotice() {
        val n = notice("You have declined a mandate of INR 199.00 on GPAY - Axis Bank", "VM-AXISBK")
        assertEquals(NoticeKind.MANDATE_DECLINED, n.kind)
        assertEquals("199.00", n.amount)
    }

    @Test
    fun challanPayment_isAnExpense() {
        val body = "Your challan payment of Rs. 500 has been done successfully against challan no. TN12345678901234 and vehicle no. TN38AB1234. MoRTH"
        val t = tx(body, "VM-MORTH")
        assertFalse(t.isReceived)
        assertEquals("500", t.amount)
        assertEquals("Challan (MoRTH)", t.payee)
        assertEquals("", t.accountNumber)
        understood("VM-MORTH", body)
    }

    @Test
    fun transportParcels_areDeliveries() {
        val delivered = classify("AKR: Greetings! LR A1234567 dt 12-Jul from RAMESH TEX-CBE is delivered! Total Rs.0.0. Customer Care No: 9876543210", "AD-AKR")
        val d = delivered.content as MessageContent.Life
        assertEquals(LifeKind.DELIVERY, d.kind)
        assertEquals("Delivered", d.status)
        assertTrue(d.isDone)
        assertEquals("A1234567", d.reference)
        assertEquals("AKR", d.title)
        assertEquals(MessageCategory.COURIER, delivered.category)
        val arrived = classify("AKR: Greetings! LR A1234567 dt 12-Jul from RAMESH TEX-CBE has reached CITN office (Ph:04222345678). Collect soon to avoid demurrage. Total is Rs.350", "AD-AKR")
        val a = arrived.content as MessageContent.Life
        assertFalse(a.isDone)
        assertTrue(a.status, a.status.startsWith("Arrived at office"))
        assertTrue(a.status.contains("350"))
        assertNull(a.amount)
        val today = LocalDate.of(2026, 8, 12)
        assertEquals(1, LifeAgenda.build(listOf(arrived), today, ZoneOffset.UTC).size)
        understood("AD-AKR", delivered.message.body, arrived.message.body)
    }

    // ---- E. promotions and notices

    @Test
    fun smyttenMessages_arePromotional() {
        val bodies = listOf(
            "Account Notification: 50 Pts + FREE Gift only for Rs.199 has been added to your Smytten profile. Claim now https://smy.tn/abc",
            "TGIF Deal! Get Free Lakme Lipsticks* worth Rs.699 on orders above Rs.499. Shop now https://smy.tn/xyz -Smytten",
            "Smytten update: Black Friday Sale is LIVE! Get upto 70% off + free samples worth Rs.999 https://smy.tn/bf"
        )
        bodies.forEach { assertTrue(it, classify(it, "VM-SMYTTN").isPromotional) }
        understood("VM-SMYTTN", *bodies.toTypedArray())
    }

    @Test
    fun telecomAdvisory_isAnAdvisoryWithoutAnAmount() {
        val n = notice("Advisory as per the Telecommunications Act 2023. Acquiring SIMs in the name of others or fraud/forgery for telecom services is punishable with imprisonment up to 3 years and/or fine up to Rs.50 lakh.", "VM-DOTSMS")
        assertEquals(NoticeKind.ADVISORY, n.kind)
        assertNull(n.amount)
    }

    private val expired = "ரீசார்ஜ் திட்டம் 12-Aug-26 10:30 Hrs அன்று முடிவடைந்துவிட்டது ! Jio எண் : 9876543210 திட்டத்தின் பெயர்: Rs239_28D_1.5GB/D_42GB ரீசார்ஜ் செய்ய - https://jio.com/r"
    private val expiring = "தற்போதைய ரீசார்ஜ் திட்டம் 14-Aug-26 10:30 Hrs அன்று முடிவடைகிறது! Jio எண் : 9876543210 திட்டத்தின் பெயர்: Rs239_28D_1.5GB/D_42GB ரீசார்ஜ் செய்ய - https://jio.com/r"
    private val renewed = "Jio எண் 9876543210 திட்டம் Rs239_28D_1.5GB/D_42GB முடிவடைந்துவிட்டது. நீங்கள் ஏற்கனவே Rs299 ரீசார்ஜ் செய்துள்ளீர்கள். புதிய திட்டம் தானாகவே தொடங்கும்."

    @Test
    fun jioTamilPlanMessages_areRenewalReminders() {
        val e = life(expired, "JD-JIOINF")
        assertEquals(LifeKind.RENEWAL, e.kind)
        assertEquals("Plan expired", e.status)
        assertEquals(LocalDate.of(2026, 8, 12), e.date)
        assertTrue(e.isDone)
        val x = classify(expiring, "JD-JIOINF")
        val l = x.content as MessageContent.Life
        assertEquals("Plan expires", l.status)
        assertEquals(LocalDate.of(2026, 8, 14), l.date)
        assertEquals(java.time.LocalTime.of(10, 30), l.time)
        assertEquals("Jio", l.title)
        assertEquals(MessageCategory.NETWORK_CARRIERS, x.category)
        // it shows in the agenda before it ends
        assertEquals(1, LifeAgenda.build(listOf(x), LocalDate.of(2026, 8, 12), ZoneOffset.UTC).size)
        val r = life(renewed, "JD-JIOINF")
        assertEquals("Plan renewed", r.status)
        assertTrue(r.isDone)
        understood("JD-JIOINF", expired, expiring, renewed)
    }

    @Test
    fun jioOfferAndDataAlert() {
        val offer = classify("Jio வை அழைத்ததற்கு நன்றி! இன்றே 5G அன்லிமிடெட் டேட்டாவை Rs.299க்கு பெறுங்கள். https://jio.com/x", "JD-JIOINF")
        assertTrue(offer.isPromotional)
        val alert = "DATA Usage Alert! 90% of available data used as on 12-Aug-26 10:30 Jio Number : 9876543210 Total Data quota as per plan : 42.00 GB Balance Data quota : 4.20 GB Plan Name : Rs239_28D_1.5GB/D_42GB"
        val d = classify(alert, "JD-JIOINF").content as MessageContent.DataUsage
        assertEquals(NetworkCarrier.JIO, d.carrier)
        assertEquals(90, d.percentUsed)
        understood("JD-JIOINF", offer.message.body, alert)
    }

    @Test
    fun airtelDataAlertStillAirtel() {
        val d = classify("80% Alert: You have consumed 80% of your daily high Speed Data limit. Track usage on Airtel Thanks App i.airtel.in/Get-Data", "AD-AIRTEL").content
        assertTrue(d is MessageContent.DataUsage && d.carrier == NetworkCarrier.AIRTEL)
    }
}
