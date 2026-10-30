package com.zyke.message.domain

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.accounts.CardSpend
import com.zyke.message.domain.model.BankName
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSpendTest {
    private fun account(key: String, kind: AccountKind, spent: Int) = AccountSummary(
        key = key, kind = kind, bankName = BankName.values().first(), last4 = key.takeLast(4), title = key,
        balance = null, balanceAsOf = null, spent = BigDecimal(spent), received = BigDecimal.ZERO, activityCount = 1, lastActivity = 0L,
        dueAmount = null, dueDate = null, minimumDue = null, paymentLink = null, sampleMessageId = 0L, userCard = null
    )

    @Test
    fun oneLimitIsSharedAcrossAllTheCreditCards_andBankAccountsAreNotCounted() {
        val accounts = listOf(
            account("card1111", AccountKind.CREDIT_CARD, 8_000),
            account("card2222", AccountKind.CREDIT_CARD, 6_000),
            account("acct3333", AccountKind.BANK_ACCOUNT, 50_000),
            account("debit4444", AccountKind.DEBIT_CARD, 2_000)
        )
        val status = CardSpend.status(accounts, BigDecimal(20_000))!!

        assertEquals(BigDecimal(14_000), status.used)
        assertEquals(BigDecimal(6_000), status.remaining)
        assertEquals(listOf("card1111", "card2222"), status.perCard.map { it.first })
        assertFalse(status.isOver)
        assertEquals(0.7f, status.fraction, 0.001f)
    }

    @Test
    fun nearAndOverAreTold_andNoLimitMeansNoStatus() {
        val near = CardSpend.status(listOf(account("card1111", AccountKind.CREDIT_CARD, 17_000)), BigDecimal(20_000))!!
        assertTrue(near.isNear)
        val over = CardSpend.status(listOf(account("card1111", AccountKind.CREDIT_CARD, 23_000)), BigDecimal(20_000))!!
        assertTrue(over.isOver)
        assertEquals(BigDecimal(3_000), over.over)
        assertEquals(1f, over.fraction, 0.001f)
        assertNull(CardSpend.status(emptyList(), null))
    }
}
