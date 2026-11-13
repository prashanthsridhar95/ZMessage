package com.zyke.message.domain

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.accounts.AccountsOverview
import com.zyke.message.domain.accounts.AccountsOverviewModel
import com.zyke.message.domain.accounts.CardDues
import com.zyke.message.domain.accounts.PullReveal
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.networth.BreakdownRow
import com.zyke.message.domain.networth.NetWorth
import com.zyke.message.domain.networth.NetWorthBreakdown
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.networth.NetWorthKind
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsOverviewModelTest {
    private val today = LocalDate.of(2026, 11, 10)

    private fun bd(v: String) = BigDecimal(v)

    private fun bank(key: String, balance: String?) = AccountSummary(
        key = key, kind = AccountKind.BANK_ACCOUNT, bankName = BankName.OTHERS, last4 = "1111", title = key, balance = balance?.let { bd(it) }, balanceAsOf = today,
        spent = BigDecimal.ZERO, received = BigDecimal.ZERO, activityCount = 0, lastActivity = 0L,
        dueAmount = null, dueDate = null, minimumDue = null, paymentLink = null, sampleMessageId = 0L, userCard = null
    )

    private fun card(
        key: String, kind: AccountKind = AccountKind.CREDIT_CARD, due: String? = null, dueDate: LocalDate? = null, unbilled: String? = null,
        paid: String? = null, part: String? = null, spent: String = "0", title: String = key
    ) = AccountSummary(
        key = key, kind = kind, bankName = BankName.OTHERS, last4 = "5580", title = title, balance = null, balanceAsOf = null,
        spent = bd(spent), received = BigDecimal.ZERO, activityCount = 0, lastActivity = 0L,
        dueAmount = due?.let { bd(it) }, dueDate = dueDate, minimumDue = null, paymentLink = null, sampleMessageId = 0L, userCard = null,
        unbilled = unbilled?.let { bd(it) }, paidSinceBill = paid?.let { bd(it) }, billPartPaid = part?.let { bd(it) }
    )

    @Test fun billedAndUnbilledAddUpAcrossCards() {
        val s = CardDues.summarize(
            listOf(
                card("a", due = "10000", dueDate = today.plusDays(5), unbilled = "2500"),
                card("b", due = "4000", dueDate = today.plusDays(2), unbilled = "500")
            ), today
        )
        assertEquals(bd("14000"), s.billed)
        assertEquals(bd("3000"), s.unbilled)
        assertEquals(bd("17000"), s.total)
        assertFalse(s.approx)
    }

    @Test fun aPaidBillIsNothingAndAPartPaidOneOnlyItsRest() {
        val s = CardDues.summarize(
            listOf(
                card("paid", due = "8000", dueDate = today.plusDays(3), unbilled = "100", paid = "8000"),
                card("part", due = "6000", dueDate = today.plusDays(3), unbilled = "0", paid = "2500"),
                card("partOnly", due = "3000", dueDate = today.plusDays(4), unbilled = "0", part = "1000")
            ), today
        )
        assertEquals(bd("5500"), s.billed)
        assertEquals(bd("0"), s.cards.first { it.key == "paid" }.billed)
        assertTrue(s.cards.first { it.key == "paid" }.billPaid)
        assertEquals(bd("2000"), s.cards.first { it.key == "partOnly" }.billed)
    }

    @Test fun anUnknownCycleCountsTheMonthsSpendingAsApproximateUnbilled() {
        val s = CardDues.summarize(listOf(card("x", spent = "1800")), today)
        assertEquals(bd("1800"), s.unbilled)
        assertTrue(s.approx)
        assertTrue(s.cards.single().unbilledApprox)
        // A known cycle is exact even when it is zero, and an unknown one with no spending is not "approx".
        assertFalse(CardDues.summarize(listOf(card("y", unbilled = "0", spent = "900")), today).approx)
        assertFalse(CardDues.summarize(listOf(card("z", spent = "0")), today).approx)
    }

    @Test fun aDebitCardOwesNothingAndBankAccountsAreIgnored() {
        val s = CardDues.summarize(listOf(card("d", kind = AccountKind.DEBIT_CARD, spent = "5000"), bank("b", "100")), today)
        assertFalse(s.hasCards)
        assertEquals(BigDecimal.ZERO, s.total)
        assertNull(s.next)
    }

    @Test fun orderingPutsTheSoonestBillFirstThenTheBiggestRest() {
        val s = CardDues.summarize(
            listOf(
                card("quiet", unbilled = "50", title = "Quiet"),
                card("late", due = "900", dueDate = today.plusDays(9), title = "Late"),
                card("soon", due = "100", dueDate = today.plusDays(1), title = "Soon"),
                card("nodate", due = "700", title = "NoDate"),
                card("big", unbilled = "9000", title = "Big")
            ), today
        )
        assertEquals(listOf("soon", "late", "nodate", "big", "quiet"), s.cards.map { it.key })
    }

    @Test fun theNextBillIsTheSoonestOneStillOwed_andOverdueIsSaid() {
        val s = CardDues.summarize(
            listOf(
                card("paid", due = "500", dueDate = today.plusDays(1), paid = "500"),
                card("b", due = "4000", dueDate = today.plusDays(6)),
                card("a", due = "1200", dueDate = today.plusDays(3))
            ), today
        )
        val next = s.next!!
        assertEquals("a", next.key)
        assertEquals(bd("1200"), next.amount)
        assertFalse(next.overdue)
        val late = CardDues.summarize(listOf(card("o", due = "300", dueDate = today.minusDays(2))), today).next!!
        assertTrue(late.overdue)
    }

    @Test fun leftAfterDuesIsTheBalanceMinusEverythingTheCardsWillAskFor() {
        val overview = AccountsOverview(
            listOf(bank("b", "50000"), card("c", due = "12000", dueDate = today.plusDays(4), unbilled = "3000")), bd("50000"), 1
        )
        val model = AccountsOverviewModel.build(overview, today)
        assertEquals(bd("35000"), model.leftAfterDues)
        val over = AccountsOverviewModel.build(AccountsOverview(overview.accounts, bd("10000"), 1), today)
        assertEquals(bd("-5000"), over.leftAfterDues)
        assertNull(AccountsOverviewModel.build(AccountsOverview(listOf(bank("b", "5")), bd("5"), 1), today).leftAfterDues)
        assertNull(AccountsOverviewModel.build(AccountsOverview(overview.accounts, null, 0), today).leftAfterDues)
    }

    @Test fun cardDuesAgreeWithTheCardLinesOfNetWorth() {
        val cards = listOf(
            card("a", due = "10000", dueDate = today.plusDays(5), unbilled = "2500", paid = "4000"),
            card("b", due = "700", paid = "700", unbilled = "20"),
            card("c", due = "1000", part = "250")
        )
        val report = NetWorth.build(emptyList(), cards, emptyList(), today)
        assertEquals(report.totalLiabilities, CardDues.summarize(cards, today).total)
    }

    @Test fun theNetWorthBreakdownAddsUpToTheReport() {
        val accounts = listOf(bank("b1", "40000"), bank("b2", "10000"), card("c", due = "5000", unbilled = "1000"))
        val items = listOf(
            NetWorthItem("i1", "Cash", NetWorthKind.CASH, bd("2000"), today),
            NetWorthItem("i2", "Owed", NetWorthKind.OTHER_DEBT, bd("700"), today)
        )
        val report = NetWorth.build(items, accounts, emptyList(), today)
        val rows: List<BreakdownRow> = NetWorthBreakdown.of(report)
        assertEquals(listOf("Bank balances", "Cash and other assets", "Card dues", "Other debts"), rows.map { it.label })
        assertEquals(report.totalAssets, rows.filter { !it.isLiability }.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
        assertEquals(report.totalLiabilities, rows.filter { it.isLiability }.fold(BigDecimal.ZERO) { a, r -> a + r.amount })
        assertEquals(bd("50000"), rows.first().amount)
    }

    // ---- the pull

    private val t = 100f

    @Test fun theStretchResistsAndNeverPassesItsMaximum() {
        assertEquals(0f, PullReveal.stretch(0f, t), 0f)
        assertEquals(0f, PullReveal.stretch(-20f, t), 0f)
        assertEquals(30f, PullReveal.stretch(50f, t), 0.001f)
        // The threshold needs a longer drag than itself (resistance), and the band beyond it is bounded.
        assertTrue(PullReveal.dragFor(t, t) > t)
        var last = 0f
        for (drag in 0..3000 step 25) {
            val s = PullReveal.stretch(drag.toFloat(), t)
            assertTrue("monotonic at $drag", s >= last)
            assertTrue("bounded at $drag", s <= t * PullReveal.MAX_FACTOR)
            last = s
        }
    }

    @Test fun dragForIsTheInverseOfStretch() {
        for (drag in listOf(10f, 80f, 166f, 200f, 300f, 600f)) {
            assertEquals(drag, PullReveal.dragFor(PullReveal.stretch(drag, t), t), drag * 0.01f)
        }
    }

    @Test fun theArmedPhaseHasHysteresis() {
        val idle = PullReveal.Phase.IDLE
        assertEquals(PullReveal.Phase.IDLE, PullReveal.phase(0f, t, idle))
        assertEquals(PullReveal.Phase.PULLING, PullReveal.phase(50f, t, idle))
        assertEquals(PullReveal.Phase.PULLING, PullReveal.phase(99f, t, PullReveal.Phase.PULLING))
        assertEquals(PullReveal.Phase.ARMED, PullReveal.phase(100f, t, PullReveal.Phase.PULLING))
        // Once armed it stays armed a little below the line, and lets go below the hysteresis point.
        assertEquals(PullReveal.Phase.ARMED, PullReveal.phase(90f, t, PullReveal.Phase.ARMED))
        assertEquals(PullReveal.Phase.PULLING, PullReveal.phase(80f, t, PullReveal.Phase.ARMED))
        assertEquals(PullReveal.Phase.PULLING, PullReveal.phase(90f, t, PullReveal.Phase.PULLING))
        assertEquals(PullReveal.Phase.IDLE, PullReveal.phase(0f, t, PullReveal.Phase.ARMED))
    }

    @Test fun onlyAnArmedPullOpensOnRelease() {
        assertTrue(PullReveal.opensOnRelease(PullReveal.Phase.ARMED))
        assertFalse(PullReveal.opensOnRelease(PullReveal.Phase.PULLING))
        assertFalse(PullReveal.opensOnRelease(PullReveal.Phase.IDLE))
        assertEquals(0.5f, PullReveal.fraction(50f, t), 0f)
        assertEquals(1f, PullReveal.fraction(500f, t), 0f)
        assertNotNull(PullReveal.THRESHOLD_DP)
    }

    @Test fun searchingNetWorthLeadsToAccountsWhereThePullIs_notStraightToThePage() {
        val hits = com.zyke.message.domain.search.SearchPages.hits("net worth")
        assertEquals(listOf(com.zyke.message.domain.search.SearchPages.ACCOUNTS), hits.map { it.id })
    }
}
