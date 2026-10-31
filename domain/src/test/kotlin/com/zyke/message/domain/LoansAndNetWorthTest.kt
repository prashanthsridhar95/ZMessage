package com.zyke.message.domain

import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.loans.LoanDebit
import com.zyke.message.domain.loans.LoanKind
import com.zyke.message.domain.loans.Loans
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.networth.LineSource
import com.zyke.message.domain.networth.NetWorth
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.networth.NetWorthKind
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoansAndNetWorthTest {
    private val today = LocalDate.of(2026, 10, 31)

    private fun loan(
        principal: String = "1000000", rate: String = "9", months: Int = 240, start: LocalDate = LocalDate.of(2026, 1, 10),
        override: String? = null, day: Int = 5, name: String = "HDFC Home Loan"
    ) = Loan("l1", name, LoanKind.HOME, BigDecimal(principal), BigDecimal(rate), months, start, override?.let { BigDecimal(it) }, day)

    @Test
    fun emiFormulaMatchesKnownFigures() {
        // The usual textbook figure for 10 lakh at 9% over 20 years.
        assertEquals(BigDecimal("8997.26"), Loans.emi(BigDecimal("1000000"), BigDecimal("9"), 240))
        // 5 lakh at 12% for a year.
        assertEquals(BigDecimal("44424.39"), Loans.emi(BigDecimal("500000"), BigDecimal("12"), 12))
        // No interest: the principal split evenly.
        assertEquals(BigDecimal("1000.00"), Loans.emi(BigDecimal("12000"), BigDecimal.ZERO, 12))
        assertEquals(0, Loans.emi(BigDecimal("12000"), BigDecimal("9"), 0).signum())
    }

    @Test
    fun scheduleSplitsInterestAndPrincipalAndClears() {
        val loan = loan()
        val schedule = Loans.schedule(loan)
        assertEquals(240, schedule.size)
        // First month: interest is 0.75% of the principal.
        assertEquals(BigDecimal("7500.00"), schedule[0].interest)
        assertEquals(BigDecimal("1497.26"), schedule[0].principal)
        assertEquals(BigDecimal("998502.74"), schedule[0].balanceAfter)
        assertEquals(0, schedule.last().balanceAfter.signum())
        // Each row's parts add up to its payment.
        assertTrue(schedule.all { it.interest + it.principal == it.amount })
        // The principal paid over the loan is the principal.
        assertEquals(0, schedule.fold(BigDecimal.ZERO) { s, r -> s + r.principal }.compareTo(BigDecimal("1000000")))
    }

    @Test
    fun outstandingAfterNPayments() {
        val loan = loan()
        assertEquals(loan.principal, Loans.outstandingAfter(loan, 0))
        assertEquals(BigDecimal("998502.74"), Loans.outstandingAfter(loan, 1))
        assertEquals(0, Loans.outstandingAfter(loan, 500).signum())
    }

    @Test
    fun largerOverrideEndsTheLoanEarly() {
        val loan = loan(principal = "100000", rate = "12", months = 12, override = "20000")
        val schedule = Loans.schedule(loan)
        assertTrue(schedule.size < 12)
        assertEquals(0, schedule.last().balanceAfter.signum())
    }

    @Test
    fun statusCountsDueInstalmentsAndNextDate() {
        val status = Loans.status(loan(), today)
        // Started 10 Jan 2026, EMI on the 5th from February: Feb..Oct = 9 due by 31 Oct.
        assertEquals(9, status.dueCount)
        assertEquals(LocalDate.of(2026, 11, 5), status.nextDueDate)
        assertEquals(LocalDate.of(2026, 10, 5), status.asOf)
        assertTrue(status.outstanding < status.loan.principal)
        assertFalse(status.isClosed)
        assertTrue(status.fraction > 0f && status.fraction < 0.1f)
    }

    @Test
    fun dueDateUsesTheShortMonthsLastDay() {
        val loan = loan(day = 31, start = LocalDate.of(2026, 1, 1))
        assertEquals(LocalDate.of(2026, 2, 28), Loans.dueDate(loan, 1))
        assertEquals(LocalDate.of(2026, 3, 31), Loans.dueDate(loan, 2))
    }

    @Test
    fun finishedLoanIsClosed() {
        val status = Loans.status(loan(principal = "12000", rate = "0", months = 12, start = LocalDate.of(2025, 1, 1)), today)
        assertTrue(status.isClosed)
        assertNull(status.nextDueDate)
        assertEquals(0, status.outstanding.signum())
    }

    @Test
    fun emiDebitsAreMatchedByNameAmountAndDay() {
        val loan = loan(start = LocalDate.of(2026, 8, 10)) // EMIs on 5 Sep and 5 Oct
        val emi = loan.emi
        val debits = listOf(
            LoanDebit(LocalDate.of(2026, 9, 5), emi, "hdfc ltd"),
            LoanDebit(LocalDate.of(2026, 10, 6), emi, "hdfc ltd"),
            LoanDebit(LocalDate.of(2026, 10, 20), emi, "hdfc ltd") // not near a due day
        )
        val status = Loans.status(loan, today, debits)
        assertEquals(2, status.dueCount)
        assertEquals(2, status.seenInMessages)
        assertEquals(0, status.notSeen)
        assertEquals(LocalDate.of(2026, 10, 6), status.lastSeenOn)
    }

    @Test
    fun unmatchedEmisAreNotSeenAndOnePaymentSettlesOneInstalment() {
        val loan = loan(start = LocalDate.of(2026, 8, 10))
        assertEquals(2, Loans.status(loan, today, emptyList()).notSeen)
        assertEquals(2, Loans.status(loan, today, listOf(LoanDebit(today, BigDecimal("5"), "x"))).notSeen)
        val one = listOf(LoanDebit(LocalDate.of(2026, 10, 5), loan.emi, "hdfc loan"))
        val status = Loans.status(loan, today, one)
        assertEquals(1, status.seenInMessages)
        // Matching never changes what the schedule says is owed.
        assertEquals(Loans.status(loan, today).outstanding, status.outstanding)
    }

    private fun account(
        key: String, kind: AccountKind = AccountKind.BANK_ACCOUNT, balance: String? = null, asOf: LocalDate? = null,
        due: String? = null, unbilled: String? = null, paid: String? = null, billedOn: LocalDate? = null, fund: Boolean = false
    ) = AccountSummary(
        key = key, kind = kind, bankName = BankName.OTHERS, last4 = "1234", title = key, balance = balance?.let { BigDecimal(it) }, balanceAsOf = asOf,
        spent = BigDecimal.ZERO, received = BigDecimal.ZERO, activityCount = 0, lastActivity = 0L,
        dueAmount = due?.let { BigDecimal(it) }, dueDate = null, minimumDue = null, paymentLink = null, sampleMessageId = 0L, userCard = null,
        isProvidentFund = fund, unbilled = unbilled?.let { BigDecimal(it) }, billedOn = billedOn, paidSinceBill = paid?.let { BigDecimal(it) }
    )

    private fun item(kind: NetWorthKind, amount: String, asOf: LocalDate = today.minusDays(5), name: String = kind.label) =
        NetWorthItem("i-$name", name, kind, BigDecimal(amount), asOf)

    @Test
    fun netWorthIsAssetsMinusLiabilities() {
        val accounts = listOf(
            account("bank", balance = "50000", asOf = today.minusDays(2)),
            account("card", AccountKind.CREDIT_CARD, due = "10000", unbilled = "2000", billedOn = today.minusDays(10))
        )
        val loanStatus = Loans.status(loan(principal = "100000", rate = "0", months = 10, start = LocalDate.of(2026, 8, 1)), today)
        val report = NetWorth.build(
            listOf(item(NetWorthKind.CASH, "5000"), item(NetWorthKind.GOLD, "100000"), item(NetWorthKind.OTHER_DEBT, "3000")),
            accounts, listOf(loanStatus), today
        )
        assertEquals(BigDecimal("155000"), report.totalAssets)
        // Card 12000 + loan (100000 less the 2 EMIs of 10000 due by now = 80000) + other debt 3000.
        assertEquals(0, report.totalLiabilities.compareTo(BigDecimal("95000")))
        assertEquals(0, report.netWorth.compareTo(BigDecimal("60000")))
        assertEquals(LineSource.MESSAGES, report.assets.first { it.name == "bank" }.source)
        assertEquals(LineSource.LOANS, report.liabilities.first { it.key.startsWith("loan-") }.source)
        assertEquals(LineSource.ENTERED, report.assets.first { it.name == "Gold" }.source)
        assertEquals(0, report.staleCount)
    }

    @Test
    fun missingValuesAreListedButNotCounted() {
        val accounts = listOf(account("bank"), account("card", AccountKind.CREDIT_CARD))
        val report = NetWorth.build(emptyList(), accounts, emptyList(), today)
        assertEquals(2, report.unknownCount)
        assertEquals(0, report.netWorth.signum())
        assertNull(report.assets.single().amount)
        assertTrue(report.liabilities.single().note.isNotEmpty())
    }

    @Test
    fun valuesOlderThanSixtyDaysAreFlagged() {
        assertTrue(NetWorth.isStale(today.minusDays(61), today))
        assertFalse(NetWorth.isStale(today.minusDays(60), today))
        assertFalse(NetWorth.isStale(null, today))
        val report = NetWorth.build(
            listOf(item(NetWorthKind.STOCKS, "1000", asOf = today.minusDays(90))),
            listOf(account("bank", balance = "10", asOf = today.minusDays(100))), emptyList(), today
        )
        assertEquals(2, report.staleCount)
    }

    @Test
    fun paidCardBillOwesOnlyWhatWasSpentAfterIt() {
        val paid = account("c", AccountKind.CREDIT_CARD, due = "10000", paid = "10000", unbilled = "500", billedOn = today)
        assertEquals(0, NetWorth.cardOwed(paid)!!.compareTo(BigDecimal("500")))
        val part = account("c", AccountKind.CREDIT_CARD, due = "10000", paid = "4000", billedOn = today)
        assertEquals(0, NetWorth.cardOwed(part)!!.compareTo(BigDecimal("6000")))
    }

    @Test
    fun providentFundBalanceIsAnAssetLabelledAsSuch() {
        val report = NetWorth.build(emptyList(), listOf(account("epf", balance = "250000", asOf = today, fund = true)), emptyList(), today)
        assertEquals("Provident fund", report.assets.single().kindLabel)
        assertEquals(BigDecimal("250000"), report.totalAssets)
    }
}
