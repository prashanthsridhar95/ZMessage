package com.zyke.message.domain.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wordings are first guesses from reports of HDFC alert emails, NOT copies of real mail. These tests pin what the parser does with
 * them, and with everything else a bank's address can send. When real samples arrive, add them here.
 */
class EmailAlertParserTest {
    private val parser = EmailAlertParser()
    private val hdfc = EmailAlertSenders.find("alerts@hdfcbank.bank.in")!!
    private val at = 1_795_000_000_000L

    private fun read(body: String, subject: String = "Alert", sender: AlertSender = hdfc) = parser.read(sender, subject, body, at, "k1")
    private fun understood(body: String, subject: String = "Alert") = (read(body, subject) as EmailRead.Understood).alert

    @Test
    fun hdfcUpiDebit_withTheMerchantInBrackets() {
        val a = understood("Dear Customer,\n\nRs.250.00 has been debited from account **1234 to VPA chai@okhdfcbank (Chai Point) on 28-11-26.\nYour UPI transaction reference number is 612345678901.\nIf you did not authorize this transaction, please report it immediately by calling 18002586161.\nWarm Regards,\nHDFC Bank")
        assertEquals(EmailAlertKind.DEBIT, a.kind)
        assertEquals("250.00", a.amount)
        assertEquals("1234", a.account)
        assertEquals("chai@okhdfcbank", a.vpa)
        assertEquals("Chai Point", a.payee)
        assertEquals("612345678901", a.reference)
        assertFalse(a.isCard)
        assertEquals("HDFC Bank", a.senderName)
        assertEquals("hdfc", a.senderId)
    }

    @Test
    fun hdfcUpiDebit_withTheNameAfterTheAddress_andThousands() {
        val a = understood("Rs.1,250.50 has been debited from account 5765 to VPA zomato@icici ZOMATO LIMITED on 01-12-2026. Your UPI transaction reference number is 998877665544.")
        assertEquals("1250.50", a.amount)
        assertEquals("ZOMATO LIMITED", a.payee)
        assertEquals("zomato@icici", a.vpa)
        assertEquals("998877665544", a.reference)
    }

    @Test
    fun hdfcUpiDebit_withNoName_usesTheUpiAddress() {
        val a = understood("Rs.40.00 has been debited from account **1234 to VPA 9876543210@ybl on 28-11-26. Your UPI transaction reference number is 612345678901.")
        assertEquals("9876543210@ybl", a.payee)
    }

    @Test
    fun hdfcUpiCredit() {
        val a = understood("Dear Customer, Rs. 500.00 is successfully credited to your account **1234 by VPA ravi@okaxis (Ravi Kumar) on 28-11-26. Your UPI transaction reference number is 612345678902.")
        assertEquals(EmailAlertKind.CREDIT, a.kind)
        assertEquals("500.00", a.amount)
        assertEquals("Ravi Kumar", a.payee)
        assertEquals("1234", a.account)
        assertEquals("612345678902", a.reference)
    }

    @Test
    fun hdfcCreditCardDebit_towardsAMerchant() {
        val a = understood("Dear Customer, Rs. 1500.00 was debited from your HDFC Bank Credit Card ending 1234 towards AMAZON PAY INDIA on 28 Nov, 2026 at 14:35:10.")
        assertEquals(EmailAlertKind.DEBIT, a.kind)
        assertTrue(a.isCard)
        assertEquals("1234", a.account)
        assertEquals("AMAZON PAY INDIA", a.payee)
        assertEquals("1500.00", a.amount)
    }

    @Test
    fun hdfcCreditCardUse_thankYouForUsing() {
        val a = understood("Thank you for using your HDFC Bank Credit Card ending 4321 for Rs 2,499.00 at SWIGGY on 28-11-2026 14:35:10.")
        assertTrue(a.isCard)
        assertEquals("4321", a.account)
        assertEquals("SWIGGY", a.payee)
        assertEquals("2499.00", a.amount)
    }

    @Test
    fun theSmsWordingIsReadToo_throughTheSmsParser() {
        val a = understood("Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo Chai Point\nOn 28/11/26\nRef 612345678901\nNot You? Call 18002586161")
        assertEquals(EmailAlertKind.DEBIT, a.kind)
        assertEquals("250.00", a.amount)
        assertEquals("1234", a.account)
        assertEquals("612345678901", a.reference)
    }

    @Test
    fun aGenericDebitInAnotherBanksWords() {
        val axis = EmailAlertSenders.find("alerts@axisbank.com")!!
        val r = read("Dear Customer, INR 320.00 was debited from your Axis Bank account XX4455 on 28-11-26. Info: UPI/P2M/612345678903/CAFE.", sender = axis)
        val a = (r as EmailRead.Understood).alert
        assertEquals("Axis Bank", a.senderName)
        assertEquals(EmailAlertKind.DEBIT, a.kind)
        assertEquals("320.00", a.amount)
    }

    @Test
    fun notPayments_areSkipped() {
        // Marketing and newsletters.
        assertEquals(EmailRead.Skipped(SkipReason.MARKETING), read("Get cashback of Rs 500 on your next spend. Offer valid till 31 Dec.", subject = "Exclusive offer: Rs 500 cashback for you"))
        assertEquals(EmailRead.Skipped(SkipReason.NOT_A_PAYMENT), read("Read our monthly newsletter on saving money. Unsubscribe any time."))
        assertEquals(EmailRead.Skipped(SkipReason.NOT_A_PAYMENT), read("Please update your KYC to keep your account active."))
        // A statement, whatever it says about money.
        assertEquals(EmailRead.Skipped(SkipReason.STATEMENT), read("Total amount due Rs 12,000.00. Minimum due Rs 600.00. Due date 15-12-26.", subject = "Your HDFC Bank Credit Card Statement for Nov 2026"))
        assertEquals(EmailRead.Skipped(SkipReason.STATEMENT), read("Your e-statement for the month is attached. Rs 5000 debited on 02-11-26.", subject = "Account statement"))
        // A one-time password that names an amount.
        assertEquals(EmailRead.Skipped(SkipReason.OTP), read("Your OTP for transaction of Rs 5000 at AMAZON is 482913. Do not share it with anyone."))
        // A balance, with no movement.
        assertEquals(EmailRead.Skipped(SkipReason.NOT_A_PAYMENT), read("Your available balance is Rs 5,000.00 as of 28-11-26."))
        // Empty and hostile text.
        assertEquals(EmailRead.Skipped(SkipReason.NOT_A_PAYMENT), read(""))
        assertTrue(read("\u0000".repeat(1000) + "Rs " + "9".repeat(5000)) !is EmailRead.Understood)
    }

    @Test
    fun aMarketingFooterCannotTurnABanksOwnAlertIntoMarketing() {
        val a = understood("Rs.250.00 has been debited from account **1234 to VPA a@b (Shop) on 28-11-26. Your UPI transaction reference number is 612345678901. Terms and conditions apply. Click here for offers. Unsubscribe.")
        assertEquals("250.00", a.amount)
    }

    @Test
    fun aMoneyMailInAnUnknownWording_isNotCounted_andIsKeptMasked() {
        val r = read("Your UPI transaction of Rs 250.00 to Chai Point from account **1234 was completed on 28-11-26. Ref 612345678901. Call 18002586161 for help.")
        assertTrue(r.toString(), r is EmailRead.NotUnderstood)
        val a = (r as EmailRead.NotUnderstood).alert
        assertEquals(EmailAlertKind.UNCLEAR, a.kind)
        assertEquals("", a.amount)
        // Digits, names and handles are masked; the snippet is short.
        assertFalse(a.snippet.any { it in '0'..'8' })
        assertTrue("Rs" in a.snippet)
        assertTrue(a.snippet.length <= EmailAlert.SNIPPET_MAX)
        assertFalse("612345678901" in a.snippet || "250" in a.snippet)
    }

    @Test
    fun theSnippetNeverHoldsAnAddressOrALink() {
        val r = read("Payment of Rs 99 to ravi@okaxis failed. See https://hdfcbank.com/x?acct=1234567890 or write to me@gmail.com. UPI transaction pending.") as EmailRead.NotUnderstood
        assertFalse("ravi@okaxis" in r.alert.snippet || "https" in r.alert.snippet || "me@gmail.com" in r.alert.snippet)
    }

    @Test
    fun theStoredAlertHoldsNoSubjectNoSenderAddressAndNoBody() {
        val a = understood("Rs.250.00 has been debited from account **1234 to VPA chai@okhdfcbank (Chai Point) on 28-11-26. Your UPI transaction reference number is 612345678901. SECRET-MARKER-IN-FOOTER", subject = "SUBJECT-MARKER")
        val text = a.toString()
        assertFalse("SUBJECT-MARKER" in text || "SECRET-MARKER" in text || "alerts@hdfcbank" in text)
    }

    @Test
    fun theBodyIsReadFromHtmlToo() {
        val html = "<html><body><p>Dear Customer,</p><p>Rs.250.00 has been debited from account **1234 to VPA chai@okhdfcbank (Chai Point) on 28-11-26.</p><p>Your UPI transaction reference number is 612345678901.</p></body></html>"
        val text = HtmlText.strip(html)
        val a = (read(text) as EmailRead.Understood).alert
        assertEquals("Chai Point", a.payee)
    }
}
