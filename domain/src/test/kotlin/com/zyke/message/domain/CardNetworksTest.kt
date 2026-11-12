package com.zyke.message.domain

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountOverride
import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.cards.CardNetworks
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.demo.DemoData
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardNetworksTest {
    private fun net(text: String) = CardNetworks.fromText(text)

    @Test
    fun namesTheNetworkFromCardWords() {
        assertEquals(CardNetwork.VISA, net("Spent on your HDFC Bank Visa Signature Credit Card XX5580"))
        assertEquals(CardNetwork.VISA, net("Rs 500 on VISA Platinum card ending 1234"))
        assertEquals(CardNetwork.VISA, net("Your Credit Card Visa ending 4455"))
        assertEquals(CardNetwork.MASTERCARD, net("Rs 500 spent on your Mastercard debit card"))
        assertEquals(CardNetwork.MASTERCARD, net("MasterCard World ending 9921"))
        assertEquals(CardNetwork.MASTERCARD, net("MASTERCARD"))
        assertEquals(CardNetwork.MASTERCARD, net("Master Card ending 1"))
        assertEquals(CardNetwork.AMEX, net("INR 500 on your AMEX card ** 1005 at SHOP"))
        assertEquals(CardNetwork.AMEX, net("American Express Platinum Travel card"))
        assertEquals(CardNetwork.AMEX, net("Amex Gold Charge card"))
        assertEquals(CardNetwork.RUPAY, net("RuPay credit card ending 1234"))
        assertEquals(CardNetwork.RUPAY, net("Your Ru Pay platinum card"))
        assertEquals(CardNetwork.DINERS, net("HDFC Diners Club Black card"))
        assertEquals(CardNetwork.DINERS, net("Dinersclub Premium"))
    }

    @Test
    fun neverGuesses() {
        assertNull(net(""))
        assertNull(net("Rs 500 spent on HDFC Bank Credit Card XX5580 at SHOP"))
        assertNull(net("SBI Card ending 1234 payment received"))
        // Not a card at all: a travel visa, a word that merely contains one, restaurants.
        assertNull(net("Your visa appointment is on Monday"))
        assertNull(net("Paid for Visa fee at VFS Global"))
        assertNull(net("Advisa Corp invoice"))
        assertNull(net("Televisa show"))
        assertNull(net("Remastercard"))
        assertNull(net("Diners and drive-ins"))
        assertNull(net("Grupay payment"))
        assertNull(net("Examex Ltd"))
        // Two networks in one text: not told.
        assertNull(net("Paid from Visa Signature card to your Amex card"))
    }

    @Test
    fun aProductNameMayJustSayVisa() {
        assertEquals(CardNetwork.VISA, CardNetworks.fromProduct("HDFC Bank", "Visa"))
        assertEquals(CardNetwork.DINERS, CardNetworks.fromProduct("HDFC Bank", "Diners"))
        assertEquals(CardNetwork.RUPAY, CardNetworks.fromProduct("Punjab National Bank", "Rupay Select"))
        assertEquals(CardNetwork.AMEX, CardNetworks.fromProduct("American Express", "Gold Charge"))
        assertEquals(CardNetwork.AMEX, CardNetworks.fromProduct("Amex", ""))
        assertEquals(CardNetwork.DINERS, CardNetworks.fromProduct("HDFC Bank", "Diners Club Black"))
        assertNull(CardNetworks.fromProduct("SBI Card", ""))
        assertNull(CardNetworks.fromProduct("HDFC Bank", "Regalia"))
        assertEquals(CardNetwork.AMEX, CardCatalog.guessNetwork("American Express", ""))
    }

    @Test
    fun theMostNamedNetworkWins_andATieIsNotTold() {
        val visa = "Spent on HDFC Visa Signature card"
        val master = "Spent on HDFC Mastercard"
        assertEquals(CardNetwork.VISA, CardNetworks.fromMessages(sequenceOf(visa, "no network here", visa, master)))
        assertNull(CardNetworks.fromMessages(sequenceOf(visa, master)))
        assertNull(CardNetworks.fromMessages(emptySequence()))
    }

    @Test
    fun theChoiceOrderIsYouThenMessagesThenProduct_andOtherMeansNone() {
        assertEquals(CardNetwork.RUPAY, CardNetworks.resolve(CardNetwork.RUPAY, CardNetwork.VISA, "HDFC", "Diners"))
        assertEquals(CardNetwork.VISA, CardNetworks.resolve(null, CardNetwork.VISA, "HDFC", "Diners"))
        assertEquals(CardNetwork.DINERS, CardNetworks.resolve(null, null, "HDFC", "Diners"))
        assertNull(CardNetworks.resolve(CardNetwork.OTHER, CardNetwork.VISA, "HDFC", "Visa"))
        assertNull(CardNetworks.resolve(null, null, "SBI Card", ""))
    }

    // ---- through the accounts ----

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private var nextId = 1L
    private fun sms(body: String, daysAgo: Long): ClassifiedMessage = MessageClassifier().classify(
        SmsMessage(nextId++, "VM-HDFCBK-S", body, today.minusDays(daysAgo).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true)
    )
    private fun spend(card: String, daysAgo: Long) = sms("Rs.250.00 spent on HDFC Bank $card XX5580 at SHOP on 10-10-26. Avl limit Rs 90,000", daysAgo)

    @Test
    fun aCardShowsTheNetworkItsMessagesName_andYouCanChooseOrClearIt() {
        val messages = listOf(spend("Visa Signature Credit Card", 3), spend("Visa Signature Credit Card", 2), spend("Credit Card", 1))
        val key = "credit_card-5580"
        val card = Accounts.build(messages, emptyList(), now, zone).accounts.single { it.key == key }
        assertEquals(CardNetwork.VISA, card.network)
        assertEquals(CardNetwork.VISA, card.detectedNetwork)
        val chosen = Accounts.build(messages, emptyList(), now, zone, overrides = mapOf(key to AccountOverride(network = CardNetwork.RUPAY))).accounts.single { it.key == key }
        assertEquals(CardNetwork.RUPAY, chosen.network)
        assertEquals(CardNetwork.VISA, chosen.detectedNetwork)
        val none = Accounts.build(messages, emptyList(), now, zone, overrides = mapOf(key to AccountOverride(network = CardNetwork.OTHER))).accounts.single { it.key == key }
        assertNull(none.network)
        assertTrue(AccountOverride(network = CardNetwork.OTHER).isEmpty.not())
    }

    @Test
    fun aCardThatNamesNoNetworkHasNoMark_andABankAccountNeverHasOne() {
        val plain = Accounts.build(listOf(spend("Credit Card", 2)), emptyList(), now, zone).accounts.single { it.isCard }
        assertNull(plain.network)
        val account = sms("Rs.100.00 debited from A/c XX7788 on 10-10-26 to VPA shop@upi (UPI 1234). Avl Bal Rs 5,000.00", 2)
        val summary = Accounts.build(listOf(account), emptyList(), now, zone, overrides = mapOf("bank_account-7788" to AccountOverride(network = CardNetwork.VISA)))
            .accounts.single { it.last4 == "7788" }
        assertEquals(AccountKind.BANK_ACCOUNT, summary.kind)
        assertNull(summary.network)
    }

    @Test
    fun aCardEntryWithAProductNameGivesItsNetwork() {
        val entry = UserCard("c", CardType.CREDIT, "5580", "My card", bankName = "HDFC Bank", variant = "Diners Club Black")
        val card = Accounts.build(listOf(spend("Credit Card", 2)), listOf(entry), now, zone).accounts.single { it.isCard }
        assertEquals(CardNetwork.DINERS, card.network)
    }

    @Test
    fun theSpokenCardNamesTheBankAndTheNetwork() {
        val c = UserCard("i", CardType.CREDIT, "5580", "", bankName = "HDFC Bank", network = CardNetwork.VISA)
        assertEquals("HDFC Bank Visa credit card ending 5580", c.spokenDescription)
        assertEquals("HDFC Bank credit card ending 5580", c.copy(network = CardNetwork.OTHER).spokenDescription)
        assertEquals("HDFC Bank credit card ending 5580", c.copy(network = null).spokenDescription)
        assertEquals("HDFC Bank account ending 5580", c.copy(type = CardType.ACCOUNT).spokenDescription)
    }

    @Test
    fun everyDemoCardThatIsACardHasANetwork() {
        val cards = DemoData.build(System.currentTimeMillis(), ZoneId.of("Asia/Kolkata")).cards
        val real = cards.filter { it.type.isCard }
        assertTrue(real.isNotEmpty())
        real.forEach { assertTrue("${it.id} has no network", it.network != null && it.network != CardNetwork.OTHER) }
    }
}
