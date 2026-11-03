package com.zyke.message.domain

import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.launch
import org.junit.Test

/** The report: "INR 2000.00 deducted from HDFC Bank A/C No 5765 towards Bajaj Finserv Mutual Fund" five times, balance unchanged. */
class RepeatedAlertsBalanceTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private val alert = "PAYMENT ALERT! \nINR 2000.00 deducted from HDFC Bank A/C No 5765 towards Bajaj Finserv Mutual Fund UMRN: HDFC0000000024044009"
    private val balanceMessage = "Rs.100.00 debited from A/c XX5765 on 01-10-26 to VPA shop@okhdfc (UPI 1234). Avl Bal Rs 50,000.00"

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDate.of(2026, 10, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun sms(body: String, date: Long) = SmsMessage(nextId++, "AD-HDFCBK", body, date, true)
    private fun total(messages: List<SmsMessage>, userBalance: Boolean = true) =
        Accounts.build(messages.map(classifier::classify), emptyList(), now, zone)

    @Test
    fun fiveAlertsOnOneDayHoursApartAreFivePayments_notCopies() {
        val inbox = listOf(sms(balanceMessage, at(1, 9))) + (0 until 5).map { sms(alert, at(5, 9 + it * 2)) }
        val kept = inbox.sortedByDescending { it.date }
        assertEquals(6, kept.size)
        val account = total(kept).accounts.single()
        assertEquals(0, BigDecimal("40000").compareTo(account.balance!!))
        assertEquals(5, account.movementCount)
    }

    @Test
    fun fiveAlikeAlertsInOneMinute_areFivePayments_inTheList_andInTheBalance() {
        val inbox = listOf(sms(balanceMessage, at(1, 9))) + (0 until 5).map { sms(alert, at(5, 13, 44) + it * 2_000L) }
        val shown = kotlinx.coroutines.runBlocking {
            val useCase = com.zyke.message.domain.usecase.ObserveMessagesUseCase(Phone(inbox), classifier, kotlinx.coroutines.Dispatchers.Unconfined)
            var latest = emptyList<com.zyke.message.domain.model.ClassifiedMessage>()
            val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { useCase().collect { latest = it } }
            job.cancel()
            latest
        }
        assertEquals(6, shown.size)
        val account = Accounts.build(shown, emptyList(), now, zone).accounts.single()
        assertEquals(0, BigDecimal("40000").compareTo(account.balance!!))
        assertEquals(5, account.movementCount)
    }

    private class Phone(private val messages: List<SmsMessage>) : com.zyke.message.domain.repository.SmsRepository {
        override fun observeInbox() = kotlinx.coroutines.flow.flowOf(messages)
    }

    @Test
    fun withNoStatedBalanceTheAccountHasNoBalanceToAdjust() {
        val inbox = (0 until 5).map { sms(alert, at(5 + it, 9)) }
        val overview = total(inbox)
        val account = overview.accounts.single()
        assertNull(account.balance)
        assertNull(overview.totalBalance)
        assertEquals(5, account.paymentCount)
    }

    @Test
    fun anOldSmallReadingRefusesTheWholeAdjustment_andSaysSo() {
        val inbox = listOf(sms(balanceMessage.replace("50,000.00", "8,000.00"), at(1, 9))) + (0 until 5).map { sms(alert, at(5 + it, 9)) }
        val account = total(inbox).accounts.single()
        assertEquals(0, BigDecimal("8000").compareTo(account.balance!!))
        assertNull(account.balanceAdjustment)
        assertEquals(true, account.adjustmentRefused)
        assertEquals(5, account.movementCount)
    }
}

class BalanceMovementTest {
    private val zone = ZoneId.of("UTC")
    private val now = LocalDate.of(2026, 10, 20).atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L
    private val alert = "PAYMENT ALERT! \nINR 2000.00 deducted from HDFC Bank A/C No 5765 towards Bajaj Finserv Mutual Fund UMRN: HDFC0000000024044009"

    private fun at(day: Int, hour: Int = 9) = LocalDate.of(2026, 10, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun sms(body: String, date: Long) = classifier.classify(SmsMessage(nextId++, "AD-HDFCBK", body, date, true))
    private val stated = "Rs.100.00 debited from A/c XX5765 on 01-10-26 to VPA shop@okhdfc (UPI 1234). Avl Bal Rs 50,000.00"

    @Test
    fun aPaymentSetAsideFromTheTotalsStillMovesTheBalance() {
        val inbox = listOf(sms(stated, at(1))) + (0 until 5).map { sms(alert, at(5 + it)) }
        val aside = inbox.map { m ->
            val tx = m.content as? com.zyke.message.domain.model.MessageContent.Transaction
            if (tx != null && tx.payee.contains("Bajaj")) m.copy(content = tx.copy(isCardPayment = true), setAside = com.zyke.message.domain.ledger.SetAside.EXCLUDED) else m
        }
        val account = Accounts.build(aside, emptyList(), now, zone).accounts.single()
        assertEquals(0, BigDecimal("40000").compareTo(account.balance!!))
        assertEquals(5, account.movementDebits)
    }

    @Test
    fun aBalanceTheUserTypedIsAReading_andLaterPaymentsAdjustIt() {
        val inbox = (0 until 5).map { sms(alert, at(5 + it)) } + sms(alert, at(12))
        val manual = com.zyke.message.domain.accounts.ManualBalance("bank_account-5765", BigDecimal("30000"), at(10))
        val overrides = mapOf("bank_account-5765" to com.zyke.message.domain.accounts.AccountOverride(manual = manual))
        val overview = Accounts.build(inbox, emptyList(), now, zone, overrides = overrides)
        val account = overview.accounts.single()
        // Only the payment after the typed balance moves it.
        assertEquals(0, BigDecimal("28000").compareTo(account.balance!!))
        assertEquals(true, account.balanceByYou)
        assertEquals(1, account.movementDebits)
        assertEquals(0, BigDecimal("28000").compareTo(overview.totalBalance!!))
    }

    @Test
    fun aLaterMessageReadingWinsOverAnOlderTypedOne() {
        val manual = com.zyke.message.domain.accounts.ManualBalance("bank_account-5765", BigDecimal("30000"), at(2))
        val inbox = listOf(sms(stated, at(3)), sms(alert, at(5)))
        val account = Accounts.build(inbox, emptyList(), now, zone, overrides = mapOf("bank_account-5765" to com.zyke.message.domain.accounts.AccountOverride(manual = manual))).accounts.single()
        assertEquals(false, account.balanceByYou)
        assertEquals(0, BigDecimal("48000").compareTo(account.balance!!))
    }

    @Test
    fun aTypedBalanceAloneListsTheAccountInTheTotal() {
        val inbox = (0 until 2).map { sms(alert, at(5 + it)) }
        val none = Accounts.build(inbox, emptyList(), now, zone)
        assertNull(none.totalBalance)
        val manual = com.zyke.message.domain.accounts.ManualBalance("bank_account-5765", BigDecimal("9000"), at(4))
        val typed = Accounts.build(inbox, emptyList(), now, zone, overrides = mapOf("bank_account-5765" to com.zyke.message.domain.accounts.AccountOverride(manual = manual)))
        assertEquals(0, BigDecimal("5000").compareTo(typed.totalBalance!!))
    }

    @Test
    fun anUnreadableBalanceIsReportedAsNotCounted() {
        val unreadable = "Rs.500.00 debited from A/c XX5765 on 06-10-26 to VPA shop@okhdfc (UPI 9). Avl Bal is not available"
        val inbox = listOf(sms(stated, at(1)), sms(alert, at(5)), sms(unreadable, at(6)))
        val account = Accounts.build(inbox, emptyList(), now, zone).accounts.single()
        assertEquals(1, account.movementDebits)
        assertEquals(1, account.paymentsNotCounted)
    }
}
