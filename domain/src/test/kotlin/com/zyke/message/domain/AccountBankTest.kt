package com.zyke.message.domain

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountOverride
import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.accounts.SenderBank
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBankTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun sms(sender: String, body: String, daysAgo: Long, hour: Int = 10): ClassifiedMessage = classifier.classify(
        SmsMessage(nextId++, sender, body, today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), true)
    )

    private fun debit(sender: String, bank: String, last4: String, amount: String, daysAgo: Long, bal: String? = null, hour: Int = 10) =
        sms(sender, "$bank: Rs.$amount debited from A/c XX$last4 on 10-10-26 to VPA shop@upi (UPI 1234)." + (bal?.let { " Avl Bal Rs $it" } ?: ""), daysAgo, hour)

    private fun credit(sender: String, bank: String, last4: String, amount: String, daysAgo: Long, hour: Int = 10) =
        sms(sender, "$bank: Rs.$amount credited to A/c XX$last4 on 10-10-26 from VPA ravi@upi (UPI 5566)", daysAgo, hour)

    private fun assertMoney(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals("expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual!!))
    }

    @Test
    fun senderHeadersAreMappedToTheirBank() {
        assertEquals(BankName.HDFC, SenderBank.of("AX-HDFCBK-S"))
        assertEquals(BankName.ICICI, SenderBank.of("VM-ICICIT"))
        assertEquals(BankName.SBI, SenderBank.of("JD-SBIINB-S"))
        assertEquals(BankName.KVB, SenderBank.of("AD-KVBANK-S"))
        assertNull(SenderBank.of("AX-PHONEPE"))
        assertNull(SenderBank.of("+919812345678"))
    }

    @Test
    fun twoBanksSharingTheSameLastFour_areTwoAccounts_andTheBusierKeepsTheOldKey() {
        val messages = listOf(
            debit("AX-HDFCBK-S", "HDFC Bank", "1805", "100", 5),
            debit("AX-HDFCBK-S", "HDFC Bank", "1805", "200", 4),
            debit("AX-HDFCBK-S", "HDFC Bank", "1805", "300", 3),
            debit("VM-ICICIT-S", "ICICI Bank", "1805", "400", 2, bal = "9,000.00")
        )
        val accounts = Accounts.build(messages, emptyList(), now, zone).accounts.filter { it.last4 == "1805" }
        assertEquals(2, accounts.size)
        val hdfc = accounts.single { it.bankName == BankName.HDFC }
        val icici = accounts.single { it.bankName == BankName.ICICI }
        assertEquals("bank_account-1805", hdfc.key)
        assertEquals("bank_account-1805-icici", icici.key)
        assertMoney("600", hdfc.spent)
        assertMoney("400", icici.spent)
        // Each account holds only the balance its own bank stated.
        assertNull(hdfc.balance)
        assertMoney("9000", icici.balance)
    }

    @Test
    fun aTransferNamingAnotherBank_doesNotRelabelTheAccount() {
        // The ICICI message mentions HDFC as the payee's bank; its sender is ICICI.
        val messages = listOf(
            sms("VM-ICICIT-S", "ICICI Bank Acct XX1805 debited Rs.5,000.00 on 12-Oct-26 & credited to HDFC Bank A/c XX9999 via IMPS. Avl Bal Rs 8,000.00", 3),
            sms("VM-ICICIT-S", "Rs.700.00 debited from A/c XX1805 on 14-10-26 to VPA hdfc.life@hdfcbank (UPI 4321). Avl Bal Rs 7,300.00", 2)
        )
        val account = Accounts.build(messages, emptyList(), now, zone).accounts.single { it.last4 == "1805" }
        assertEquals(BankName.ICICI, account.bankName)
        assertEquals("ICICI bank account", account.title)
    }

    @Test
    fun whenTheEvidenceConflicts_theBankWithMostMessagesWins() {
        // Three messages with no known sender and body words HDFC, one that says ICICI: majority is HDFC, not the first or latest.
        val messages = listOf(
            debit("XY-SHOP", "ICICI Bank", "4455", "10", 6),
            debit("AB-ALERT", "HDFC Bank", "4455", "20", 5),
            debit("AB-ALERT", "HDFC Bank", "4455", "30", 4),
            debit("AB-ALERT", "HDFC Bank", "4455", "40", 3)
        )
        val account = Accounts.build(messages, emptyList(), now, zone).accounts.single { it.last4 == "4455" }
        assertEquals(BankName.HDFC, account.bankName)
        assertEquals(BankName.HDFC, Accounts.voteBank(listOf(BankName.HDFC to 1L, BankName.ICICI to 2L, BankName.HDFC to 3L)))
        // A tie goes to the newest.
        assertEquals(BankName.ICICI, Accounts.voteBank(listOf(BankName.HDFC to 1L, BankName.ICICI to 2L)))
    }

    @Test
    fun theSenderBeatsWordsInTheBody() {
        val messages = listOf(
            sms("VM-ICICIT-S", "Rs.900.00 debited from A/c XX7788 on 10-10-26 to HDFC Bank A/c ending 1111 (IMPS)", 3),
            sms("VM-ICICIT-S", "Rs.100.00 debited from A/c XX7788 on 11-10-26 to VPA x@hdfcbank (UPI 1)", 2)
        )
        val account = Accounts.build(messages, emptyList(), now, zone).accounts.single { it.last4 == "7788" }
        assertEquals(BankName.ICICI, account.bankName)
    }

    @Test
    fun theUsersBankChoice_winsOverDetection_andSurvivesNewMessages() {
        val base = listOf(debit("AX-HDFCBK-S", "HDFC Bank", "1805", "100", 5))
        val key = "bank_account-1805"
        val chosen = mapOf(key to AccountOverride(bank = "KVB"))
        val before = Accounts.build(base, emptyList(), now, zone, overrides = chosen).accounts.single()
        assertEquals(BankName.KVB, before.bankName)
        assertEquals("Karur Vysya Bank", before.bankLabel)
        assertEquals("Karur Vysya Bank bank account", before.title)
        assertEquals(BankName.HDFC, before.detectedBank)
        assertNotNull(before.override)
        // A new message arrives; the choice is keyed by the account, so it stays.
        val after = Accounts.build(base + debit("AX-HDFCBK-S", "HDFC Bank", "1805", "50", 1), emptyList(), now, zone, overrides = chosen).accounts.single()
        assertEquals(BankName.KVB, after.bankName)
        // Without the override the detected bank comes back at once ("Reset to detected").
        assertEquals(BankName.HDFC, Accounts.build(base, emptyList(), now, zone, overrides = emptyMap()).accounts.single().bankName)
    }

    @Test
    fun aFreeTextBankIsShownAsTyped_andTheTypeCanChange() {
        val base = listOf(debit("AX-HDFCBK-S", "HDFC Bank", "1805", "100", 5, bal = "5,000.00"))
        val chosen = mapOf("bank_account-1805" to AccountOverride(bank = "Equitas Small Finance Bank", kind = AccountKind.CREDIT_CARD))
        val account = Accounts.build(base, emptyList(), now, zone, overrides = chosen).accounts.single()
        assertEquals("Equitas Small Finance Bank", account.bankLabel)
        assertEquals(AccountKind.CREDIT_CARD, account.kind)
        assertTrue(account.isCard)
        // The stored key stays the detected one, and activity is still looked up by the detected kind.
        assertEquals("bank_account-1805", account.key)
        assertEquals(false, account.messagesAreCard)
        assertEquals("Equitas Small Finance Bank credit card", account.title)
    }

    @Test
    fun aBankTypedOnTheAccountEntryInSettings_beatsDetection() {
        val base = listOf(debit("AX-HDFCBK-S", "HDFC Bank", "1805", "100", 5))
        val card = UserCard("c1", CardType.ACCOUNT, "1805", "", bankName = "KVB")
        val account = Accounts.build(base, listOf(card), now, zone).accounts.single()
        assertEquals(BankName.KVB, account.bankName)
        assertEquals("Karur Vysya Bank bank account", account.title)
        // An entry the app made itself while naming the account holds a copy of what was detected, and does not lock it.
        val auto = UserCard("named-bank_account-1805", CardType.ACCOUNT, "1805", "Salary", bankName = "HDFC")
        assertEquals(BankName.HDFC, Accounts.build(base, listOf(auto), now, zone).accounts.single().bankName)
    }

    // ---- the maths

    @Test
    fun balancesStatedWithCrAndDr_areSignedTheRightWay() {
        fun balanceOf(text: String): BigDecimal? {
            val m = sms("AD-BANK", "Rs.100.00 debited from A/c XX1234 on 10-10-26. $text", 1)
            return BalanceReader.latest(listOf(m), zone).singleOrNull()?.amount
        }
        assertMoney("1234.56", balanceOf("Avl Bal Rs 1,234.56"))
        assertMoney("1234.56", balanceOf("Bal: Rs.1,234.56CR"))
        assertMoney("1234.56", balanceOf("Total Bal:Rs.1,234.56 Cr"))
        assertMoney("-1234.56", balanceOf("Avl Bal Rs.1,234.56 Dr"))
        assertMoney("-500", balanceOf("Avl Bal INR -500.00"))
        assertMoney("-500", balanceOf("Avl Bal -Rs.500.00"))
        assertMoney("12345.67", balanceOf("Avl Bal is Rs. 12,345.67"))
        assertMoney("12345.67", balanceOf("Available Balance: INR 12,345.67"))
        assertMoney("99", balanceOf("Balance in A/c XX1234 is Rs 99"))
        // The credit limit and what is due are never a balance.
        assertNull(balanceOf("Avl Credit Limit Rs 50,000"))
        assertNull(balanceOf("Total Due Rs 3,000 Min Due Rs 200"))
    }

    @Test
    fun aDebitWhoseBalanceCannotBeRead_isNotSubtractedFromAnOlderOne() {
        val messages = listOf(
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "100", 10, bal = "10,000.00"),
            // "Avl Bal 4500" has no currency, so it cannot be read; before, the debit was taken off the old balance anyway.
            sms("AX-HDFCBK-S", "Rs.5,500.00 debited from A/c XX1234 on 15-10-26 to VPA shop@upi (UPI 99). Avl Bal 4500.00", 4)
        )
        val account = Accounts.build(messages, emptyList(), now, zone).accounts.single()
        assertMoney("10000", account.balance)
        assertNull(account.balanceAdjustment)
    }

    @Test
    fun spendingWithNoCreditsSeen_neverMakesAStatedPositiveBalanceNegative() {
        val messages = listOf(
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "100", 30, bal = "2,000.00"),
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "1500", 10),
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "1500", 5),
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "1500", 2)
        )
        val overview = Accounts.build(messages, emptyList(), now, zone)
        val account = overview.accounts.single()
        // The statement stands; the adjustment that would have gone below zero is not applied.
        assertMoney("2000", account.balance)
        assertNull(account.balanceAdjustment)
        assertMoney("2000", overview.totalBalance)
    }

    @Test
    fun anAdjustmentThatStaysPositive_isStillApplied() {
        val messages = listOf(
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "100", 10, bal = "5,000.00"),
            debit("AX-HDFCBK-S", "HDFC Bank", "1234", "1000", 2),
            credit("AX-HDFCBK-S", "HDFC Bank", "1234", "300", 1)
        )
        val account = Accounts.build(messages, emptyList(), now, zone).accounts.single()
        assertMoney("4300", account.balance)
        assertMoney("-700", account.balanceAdjustment)
    }

    @Test
    fun aGenuineOverdraftIsKeptNegative() {
        val messages = listOf(debit("AX-HDFCBK-S", "HDFC Bank", "1234", "100", 3, bal = "1,200.00 Dr"))
        val account = Accounts.build(messages, emptyList(), now, zone).accounts.single()
        assertMoney("-1200", account.balance)
    }
}

class AccountKeysAreUniqueTest {
    @Test fun aCardTheUserAddedThatDoesNotMatchItsAccount_neverRepeatsAKey() {
        val classifier = com.zyke.message.domain.classification.MessageClassifier()
        val now = java.time.Instant.parse("2026-11-03T06:00:00Z")
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val msg = classifier.classify(com.zyke.message.domain.model.SmsMessage(1, "AD-HDFCBK",
            "Rs.1,000.00 debited from a/c **5765 on 20-10-26 to VPA shop@upi (UPI Ref 4123). Avl Bal Rs 50,000.00", now.toEpochMilli() - 86_400_000L, true))
        // the same last four, added as a credit card AND as an account entry: both must not surface under one key twice
        val cards = listOf(
            com.zyke.message.domain.model.UserCard(id = "a", last4 = "5765", bankName = "HDFC", type = com.zyke.message.domain.model.CardType.CREDIT, name = "x"),
            com.zyke.message.domain.model.UserCard(id = "b", last4 = "5765", bankName = "HDFC", type = com.zyke.message.domain.model.CardType.ACCOUNT, name = "y")
        )
        val overview = com.zyke.message.domain.accounts.Accounts.build(listOf(msg), cards, now, zone)
        org.junit.Assert.assertEquals(overview.accounts.map { it.key }.distinct(), overview.accounts.map { it.key })
    }
}
