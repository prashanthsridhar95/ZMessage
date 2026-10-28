package com.zyke.message.domain

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun sms(body: String, on: LocalDate) = classifier.classify(
        SmsMessage(nextId++, "AD-HDFCBK", body, on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true)
    )

    @Test
    fun anAccountIsBuiltFromItsMessages_withBalanceAndThisCyclesSpend() {
        val messages = listOf(
            sms("Rs.500.00 debited from A/c XX5765 on 10-10-26 to VPA shop@okhdfc (UPI 1234). Avl Bal Rs 12,000.50", today.minusDays(10)),
            sms("Rs.300.00 debited from A/c XX5765 on 18-10-26 to VPA shop@okhdfc (UPI 4321). Avl Bal Rs 2,100.00", today.minusDays(2)),
            sms("Credit Alert! Rs.1000.00 credited to HDFC Bank A/c XX5765 on 19-10-26 from VPA ravi@okhdfc (UPI 5566)", today.minusDays(1))
        )
        val overview = Accounts.build(messages, emptyList(), now, zone)
        val account = overview.accounts.single()

        assertEquals("5765", account.last4)
        assertEquals(AccountKind.BANK_ACCOUNT, account.kind)
        // The credit after the last stated balance is added to it.
        assertEquals(0, BigDecimal("3100").compareTo(account.balance!!))
        assertEquals(0, BigDecimal("800").compareTo(account.spent))
        assertEquals(0, BigDecimal("1000").compareTo(account.received))
        assertEquals(0, BigDecimal("3100").compareTo(overview.totalBalance!!))
    }

    @Test
    fun theUsersOwnNameWinsAndBalanceHistoryIsOldestFirst() {
        val messages = listOf(
            sms("Rs.500.00 debited from A/c XX5765 on 10-10-26 to VPA shop@okhdfc (UPI 1234). Avl Bal Rs 12,000.50", today.minusDays(10)),
            sms("Rs.300.00 debited from A/c XX5765 on 18-10-26 to VPA shop@okhdfc (UPI 4321). Avl Bal Rs 2,100.00", today.minusDays(2))
        )
        val named = Accounts.build(messages, listOf(UserCard("c", CardType.ACCOUNT, "5765", "Salary account")), now, zone).accounts.single()
        assertEquals("Salary account", named.title)
        assertNotNull(named.userCard)

        val history = BalanceReader.history(messages, zone).getValue("5765")
        assertEquals(2, history.size)
        assertTrue(history.first().asOf < history.last().asOf)
    }
}
