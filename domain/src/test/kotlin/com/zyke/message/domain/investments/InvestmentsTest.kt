package com.zyke.message.domain.investments

import com.zyke.message.domain.networth.NetWorth
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

class InvestmentsTest {
    private val today = LocalDate.of(2026, 11, 4)
    private fun bd(s: String) = BigDecimal(s)

    private fun fd(
        id: String = "fd1", invested: String = "100000", rate: String = "7", months: Int? = 12, start: LocalDate = LocalDate.of(2026, 3, 12),
        compounding: Compounding = Compounding.QUARTERLY, given: String? = null, maturityDate: LocalDate? = null, payout: Payout = Payout.CUMULATIVE,
        status: HoldingStatus = HoldingStatus.ACTIVE, institution: String = "City Credit Society"
    ) = Holding(
        id, InvestmentKind.FIXED_DEPOSIT, "FD $id", institution, bd(invested), startDate = start, status = status,
        terms = Terms(bd(rate), compounding, months, maturityDate, given?.let { bd(it) }, payout)
    )

    private fun rd(start: LocalDate = LocalDate.of(2026, 1, 5), months: Int = 12, installment: String = "5000", day: Int? = 5, reminder: Boolean = false) = Holding(
        "rd1", InvestmentKind.RECURRING_DEPOSIT, "RD", "Bank", startDate = start,
        terms = Terms(bd("6.5"), Compounding.QUARTERLY, months, monthlyInstallment = bd(installment), installmentDay = day, installmentReminder = reminder)
    )

    private fun mf(invested: String = "100000", current: String? = "125000", start: LocalDate? = LocalDate.of(2024, 11, 4), asOf: LocalDate? = today) =
        Holding("mf1", InvestmentKind.MUTUAL_FUND, "Index fund", "AMC", bd(invested), current?.let { bd(it) }, asOf, start)

    // ---- deposits

    @Test fun fd_quarterly_oneYear() {
        // 100000 * 1.0175^4 = 107185.90
        assertEquals(bd("107186"), InvestmentMath.maturityValue(fd()))
    }

    @Test fun fd_interestAtMaturity() = assertEquals(bd("7186"), InvestmentMath.interestAtMaturity(fd()))

    @Test fun fd_simpleInterest() {
        assertEquals(bd("107000"), InvestmentMath.maturityValue(fd(compounding = Compounding.SIMPLE)))
    }

    @Test fun fd_monthlyCompounding() {
        // 100000 * (1 + .07/12)^12 = 107229.01
        assertEquals(bd("107229"), InvestmentMath.maturityValue(fd(compounding = Compounding.MONTHLY)))
    }

    @Test fun fd_partYear_usesMonths() {
        // 6 months: 100000 * 1.0175^2
        assertEquals(bd("103531"), InvestmentMath.maturityValue(fd(months = 6)))
    }

    @Test fun fd_maturityAmountGiven_overrides() {
        val h = fd(given = "108500")
        assertEquals(bd("108500"), InvestmentMath.maturityValue(h))
        assertEquals(bd("8500"), InvestmentMath.interestAtMaturity(h))
    }

    @Test fun fd_asGiven_needsAmount_elseUnknown() {
        assertNull(InvestmentMath.maturityValue(fd(compounding = Compounding.AT_MATURITY_AS_GIVEN)))
        assertEquals(bd("110000"), InvestmentMath.maturityValue(fd(compounding = Compounding.AT_MATURITY_AS_GIVEN, given = "110000")))
    }

    @Test fun fd_periodicPayout_keepsPrincipal_andPaysInterest() {
        val h = fd(payout = Payout.PERIODIC)
        assertEquals(bd("100000"), InvestmentMath.maturityValue(h))
        assertEquals(bd("7000"), InvestmentMath.interestAtMaturity(h))
        assertEquals(bd("1750"), InvestmentMath.periodicPayout(h)) // quarterly
        assertEquals(bd("100000"), InvestmentMath.accruedValue(h, today))
    }

    @Test fun maturity_derivedFromTenure_withMonthEndClamp() {
        val h = fd(start = LocalDate.of(2026, 1, 31), months = 1)
        assertEquals(LocalDate.of(2026, 2, 28), InvestmentMath.maturityDate(h))
        assertEquals(LocalDate.of(2028, 2, 29), InvestmentMath.maturityDate(fd(start = LocalDate.of(2026, 8, 31), months = 18)))
    }

    @Test fun tenure_derivedFromMaturityDate() {
        val h = fd(months = null, maturityDate = LocalDate.of(2027, 3, 12))
        assertEquals(12, InvestmentMath.tenureMonths(h))
        assertEquals(bd("107186"), InvestmentMath.maturityValue(h))
        val odd = fd(months = null, maturityDate = LocalDate.of(2027, 3, 20))
        assertNull(InvestmentMath.tenureMonths(odd))
        // 373 days / 365 years
        val expected = 100000 * Math.pow(1.0175, 4.0 * 373 / 365)
        assertEquals(expected, InvestmentMath.maturityValue(odd)!!.toDouble(), 1.0)
    }

    @Test fun accrued_isMonotonic_andBounded() {
        val h = fd()
        var last = BigDecimal.ZERO
        var d = h.startDate!!.minusDays(3)
        while (d.isBefore(LocalDate.of(2027, 4, 1))) {
            val v = InvestmentMath.accruedValue(h, d)!!
            assertTrue("$d $v < $last", v >= last)
            assertTrue(v >= h.invested && v <= bd("107186"))
            last = v
            d = d.plusDays(5)
        }
        assertEquals(bd("107186"), InvestmentMath.accruedValue(h, LocalDate.of(2027, 3, 12)))
        assertEquals(bd("100000"), InvestmentMath.accruedValue(h, h.startDate!!))
    }

    @Test fun accrued_withGivenAmount_reachesIt() {
        val h = fd(given = "109000")
        assertEquals(bd("109000"), InvestmentMath.accruedValue(h, LocalDate.of(2027, 3, 12)))
        val mid = InvestmentMath.accruedValue(h, LocalDate.of(2026, 9, 12))!!
        assertTrue(mid > bd("100000") && mid < bd("109000"))
    }

    @Test fun daysToMaturity_andOverdue() {
        val h = fd(start = LocalDate.of(2025, 11, 12), months = 12)
        assertEquals(8L, InvestmentMath.daysToMaturity(h, today))
        assertFalse(InvestmentMath.maturityOverdue(h, today))
        val old = fd(start = LocalDate.of(2025, 3, 1), months = 12)
        assertTrue(InvestmentMath.maturityOverdue(old, today))
        assertEquals(HoldingStatus.MATURED, InvestmentMath.suggestedStatus(old, today))
        assertEquals(HoldingStatus.CLOSED, InvestmentMath.suggestedStatus(old.copy(status = HoldingStatus.CLOSED), today))
    }

    // ---- recurring deposits

    @Test fun rd_maturity_standardFormula() {
        // sum_{k=1..12} 5000 * (1 + .065/4)^(4*(13-k)/12) = 62143.23 (independent calculation); allow rounding of 1 rupee.
        assertEquals(62143.0, InvestmentMath.maturityValue(rd())!!.toDouble(), 1.0)
        assertEquals(bd("60000"), InvestmentMath.totalDeposit(rd()))
    }

    @Test fun rd_givenAmount_overrides() {
        val h = rd().let { it.copy(terms = it.terms.copy(maturityAmountGiven = bd("62500"))) }
        assertEquals(bd("62500"), InvestmentMath.maturityValue(h))
    }

    @Test fun rd_installmentsPaid_countedFromStart() {
        val h = rd(start = LocalDate.of(2026, 1, 5), day = 5)
        // 5 Jan .. 5 Nov is 11 installments by 5 Nov; on 4 Nov, 10.
        assertEquals(10, InvestmentMath.installmentsPaid(h, today))
        assertEquals(11, InvestmentMath.installmentsPaid(h, LocalDate.of(2026, 11, 5)))
        assertEquals(12, InvestmentMath.installmentsPaid(h, LocalDate.of(2030, 1, 1)))
        assertEquals(2, InvestmentMath.installmentsRemaining(h, today))
        assertEquals(LocalDate.of(2026, 11, 5), InvestmentMath.nextInstallment(h, today))
        assertEquals(0, InvestmentMath.installmentsPaid(h, LocalDate.of(2025, 12, 1)))
    }

    @Test fun rd_override_wins() {
        val h = rd().let { it.copy(terms = it.terms.copy(installmentsPaidOverride = 4)) }
        assertEquals(4, InvestmentMath.installmentsPaid(h, today))
        assertEquals(bd("20000"), InvestmentMath.invested(h, today))
    }

    @Test fun rd_monthEndInstallmentDay_clamps() {
        val h = rd(start = LocalDate.of(2026, 1, 31), day = 31, months = 4)
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)),
            InvestmentMath.installmentDates(h).toList()
        )
    }

    @Test fun rd_accrued_monotonic_between_deposit_and_maturity() {
        val h = rd()
        var last = BigDecimal.ZERO
        var d = LocalDate.of(2026, 1, 1)
        val maturity = InvestmentMath.maturityValue(h)!!
        while (d.isBefore(LocalDate.of(2027, 3, 1))) {
            val v = InvestmentMath.accruedValue(h, d)!!
            assertTrue("$d", v >= last)
            assertTrue(v <= maturity)
            assertTrue(v >= InvestmentMath.invested(h, d))
            last = v
            d = d.plusDays(3)
        }
        assertEquals(maturity, InvestmentMath.accruedValue(h, LocalDate.of(2027, 1, 5)))
    }

    // ---- funds, shares, gold

    @Test fun mf_gain_andCagr() {
        val v = Portfolio.value(mf(), today)
        assertEquals(bd("25000"), v.gain)
        assertEquals(25.0, v.gainPercent!!, 0.001)
        assertEquals(11.8, v.cagr!!, 0.1)
        assertFalse(v.estimated)
    }

    @Test fun cagr_ignoredWhenShortOrNil() {
        assertNull(InvestmentMath.cagr(bd("110"), bd("100"), today.minusDays(20), today))
        assertNull(InvestmentMath.cagr(BigDecimal.ZERO, bd("100"), today.minusDays(400), today))
        assertNull(InvestmentMath.cagr(bd("110"), bd("100"), null, today))
        assertNotNull(InvestmentMath.cagr(bd("110"), bd("100"), today.minusDays(30), today))
    }

    @Test fun staleAfter45Days() {
        assertFalse(InvestmentMath.isStale(mf(asOf = today.minusDays(45)), today))
        assertTrue(InvestmentMath.isStale(mf(asOf = today.minusDays(46)), today))
        assertFalse(InvestmentMath.isStale(fd(), today))
    }

    @Test fun updateValue_appendsHistory_capped() {
        var h = mf()
        repeat(70) { h = InvestmentMath.withValue(h, bd("1000").plus(BigDecimal(it)), today.minusDays((70 - it).toLong())) }
        assertEquals(Holding.MAX_HISTORY, h.history.size)
        assertEquals(h.history.sortedBy { it.date }, h.history)
        // same day replaces rather than duplicates
        val again = InvestmentMath.withValue(h, bd("5"), h.valueAsOf!!)
        assertEquals(h.history.size, again.history.size)
    }

    // ---- summary

    @Test fun summary_totals_allocation_andFlags() {
        val holdings = listOf(
            fd(), // active, accrued estimate
            mf(),
            mf(current = null, asOf = null).copy(id = "mf2", invested = bd("50000")),
            fd(id = "closed", status = HoldingStatus.CLOSED),
            fd(id = "renewed", status = HoldingStatus.RENEWED)
        )
        val s = Portfolio.summarize(holdings, bd("200000"), today)
        assertEquals(3, s.count)
        assertEquals(2, s.closed.size)
        val fdNow = InvestmentMath.accruedValue(fd(), today)!!
        assertEquals(bd("100000") + bd("100000") + bd("50000"), s.totalInvested)
        assertEquals(fdNow + bd("125000") + bd("50000"), s.totalCurrent)
        assertEquals(s.totalCurrent + bd("200000"), s.totalValue)
        assertEquals(s.totalCurrent - s.totalInvested, s.gain)
        assertEquals(1, s.estimatedCount)
        assertEquals(1, s.missingCount)
        assertEquals(1.0, s.allocation.sumOf { it.share }, 0.0001)
        assertTrue(s.allocation.any { it.key == Portfolio.PF_KEY })
        assertEquals(listOf("fd1"), s.maturities.map { it.holding.id })
        assertTrue(s.rows.first().holding.isDeposit)
    }

    @Test fun summary_enteredValueBeatsEstimate_forDeposit() {
        val h = fd().copy(currentValue = bd("104000"), valueAsOf = today)
        val s = Portfolio.summarize(listOf(h), null, today)
        assertEquals(bd("104000"), s.totalCurrent)
        assertEquals(0, s.estimatedCount)
    }

    @Test fun summary_empty_isSafe() {
        val s = Portfolio.summarize(emptyList(), null, today)
        assertTrue(s.isEmpty)
        assertEquals(BigDecimal.ZERO, s.totalValue)
        assertNull(s.gainPercent)
    }

    @Test fun summary_overdue() {
        val old = fd(start = LocalDate.of(2025, 3, 1))
        assertEquals(listOf("fd1"), Portfolio.summarize(listOf(old), null, today).overdue.map { it.id })
        assertTrue(Portfolio.summarize(listOf(old.copy(status = HoldingStatus.MATURED)), null, today).overdue.isEmpty())
    }

    // ---- validation

    @Test fun validation_rejectsBadInput() {
        val ok = fd()
        assertTrue(HoldingCheck.validate(ok, today).isEmpty())
        assertTrue("invested" in HoldingCheck.validate(ok.copy(invested = BigDecimal.ZERO), today))
        assertTrue("invested" in HoldingCheck.validate(ok.copy(invested = bd("-5")), today))
        assertTrue("rate" in HoldingCheck.validate(ok.copy(terms = ok.terms.copy(ratePercent = bd("26"))), today))
        assertTrue("rate" in HoldingCheck.validate(ok.copy(terms = ok.terms.copy(ratePercent = bd("-1"))), today))
        assertTrue("tenure" in HoldingCheck.validate(ok.copy(terms = ok.terms.copy(tenureMonths = null)), today))
        assertTrue("maturity" in HoldingCheck.validate(ok.copy(terms = ok.terms.copy(tenureMonths = null, maturityDate = ok.startDate)), today))
        assertTrue("name" in HoldingCheck.validate(ok.copy(name = " "), today))
        assertTrue("startDate" in HoldingCheck.validate(ok.copy(startDate = null), today))
        assertTrue("installment" in HoldingCheck.validate(rd(installment = "0"), today))
        assertTrue("installmentDay" in HoldingCheck.validate(rd(day = 32), today))
        assertTrue("currentValue" in HoldingCheck.validate(mf(current = "-1"), today))
        assertTrue(HoldingCheck.validate(mf(), today).isEmpty())
    }

    // ---- reminders

    @Test fun reminders_defaultLeads_sevenDaysAndOnTheDay() {
        val h = fd(start = LocalDate.of(2025, 11, 12), months = 12)
        val r = InvestmentReminders.build(listOf(h), today)
        assertEquals(listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12)), r.map { it.date })
        assertTrue(r.all { it.time.hour == 9 })
        assertEquals("inv:fd1:mat:2026-11-12", r.last().id)
        assertEquals("Your FD at City Credit Society matures on 12 Nov 2026 (₹1,07,186)", r.last().title)
    }

    @Test fun reminders_pastLeadsSkipped_andCustomLeads() {
        val h = fd(start = LocalDate.of(2025, 11, 12), months = 12).let { it.copy(terms = it.terms.copy(remindLeadDays = setOf(30, 15, 7, 3, 1, 0))) }
        val r = InvestmentReminders.build(listOf(h), today)
        // 30 and 15 days before are in the past already
        assertEquals(listOf(5, 9, 11, 12), r.map { it.date.dayOfMonth })
    }

    @Test fun reminders_noneForClosedMaturedRenewedOrMutualFunds() {
        val base = fd(start = LocalDate.of(2025, 11, 12))
        listOf(HoldingStatus.CLOSED, HoldingStatus.MATURED, HoldingStatus.RENEWED).forEach {
            assertTrue(InvestmentReminders.build(listOf(base.copy(status = it)), today).isEmpty())
        }
        assertTrue(InvestmentReminders.build(listOf(mf()), today).isEmpty())
    }

    @Test fun reminders_passedMaturityHasNone() {
        assertTrue(InvestmentReminders.build(listOf(fd(start = LocalDate.of(2025, 3, 1))), today).isEmpty())
    }

    @Test fun reminders_rdInstallments_offByDefault_thenTwoDaysBefore() {
        assertTrue(InvestmentReminders.build(listOf(rd()), today).none { it.id.contains(":rd:") })
        val early = LocalDate.of(2026, 10, 1)
        val on = InvestmentReminders.build(listOf(rd(reminder = true)), early).filter { it.id.contains(":rd:") }
        assertEquals(listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 12, 3)), on.map { it.date })
        assertEquals("RD installment \u20b95,000 due on the 5th", on.first().title)
        // a reminder day that has passed is skipped
        assertEquals(listOf(LocalDate.of(2026, 12, 3)), InvestmentReminders.build(listOf(rd(reminder = true)), today).filter { it.id.contains(":rd:") }.map { it.date })
    }

    @Test fun reminders_rdMonthEnd_clamps() {
        val h = rd(start = LocalDate.of(2026, 1, 31), day = 31, months = 12, reminder = true)
        val on = InvestmentReminders.build(listOf(h), LocalDate.of(2026, 2, 1)).filter { it.id.contains(":rd:") }
        assertEquals(LocalDate.of(2026, 2, 26), on.first().date) // Feb 28 minus two days
    }

    @Test fun reminders_idsStable_andUnique() {
        val h = listOf(fd(), fd(id = "fd2"), rd(reminder = true))
        val a = InvestmentReminders.build(h, today)
        val b = InvestmentReminders.build(h, today)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.size, a.map { it.id }.toSet().size)
        a.forEach { assertEquals(it.holdingId, ManagedReminder.holdingIdOf(it.id)) }
        assertEquals(null, ManagedReminder.holdingIdOf("user-reminder"))
    }

    @Test fun reminders_amountMaskedByTheSharedMasker() {
        val r = InvestmentReminders.build(listOf(fd(start = LocalDate.of(2025, 11, 12))), today).first()
        assertFalse(com.zyke.message.domain.privacy.AmountMasker.mask(r.title).contains("1,07"))
    }

    @Test fun inr_grouping() {
        assertEquals("₹1,07,186", InvestmentReminders.inr(bd("107186")))
        assertEquals("₹999", InvestmentReminders.inr(bd("999")))
        assertEquals("₹12,34,56,789", InvestmentReminders.inr(bd("123456789")))
    }

    // ---- store

    private class Saved { var last: Holdings? = null; var saves = 0 }

    private fun store(vararg h: Holding, saved: Saved = Saved()) = HoldingStore(Holdings(h.toList())) { saved.last = it; saved.saves++ }

    @Test fun store_addUpdateRemove_withExactUndo() {
        val saved = Saved()
        val s = store(saved = saved)
        val before0 = s.state.value
        val u1 = s.add(mf())
        assertEquals(1, s.state.value.holdings.size)
        val u2 = s.update(mf().copy(name = "Renamed"))
        assertEquals("Renamed", s.state.value.find("mf1")!!.name)
        s.undo(u2)
        assertEquals("Index fund", s.state.value.find("mf1")!!.name)
        val u3 = s.remove("mf1")
        assertTrue(s.state.value.holdings.isEmpty())
        s.undo(u3)
        assertEquals(mf(), s.state.value.find("mf1"))
        s.undo(u1)
        assertEquals(before0, s.state.value)
        assertEquals(saved.last, s.state.value)
    }

    @Test fun store_updateValue_closeMatured_undo() {
        val s = store(mf(), fd())
        val u = s.updateValue("mf1", bd("130000"), today)!!
        assertEquals(bd("130000"), s.state.value.find("mf1")!!.currentValue)
        assertEquals(1, s.state.value.find("mf1")!!.history.size)
        s.undo(u)
        assertEquals(mf(), s.state.value.find("mf1"))
        val c = s.close("fd1", bd("107000"), today)!!
        assertEquals(HoldingStatus.CLOSED, s.state.value.find("fd1")!!.status)
        s.undo(c)
        assertEquals(HoldingStatus.ACTIVE, s.state.value.find("fd1")!!.status)
        assertNull(s.markMatured("nope"))
        s.markMatured("fd1")
        assertEquals(HoldingStatus.MATURED, s.state.value.find("fd1")!!.status)
    }

    @Test fun store_renew_createsSuccessor_andUndoRemovesIt() {
        val old = fd(start = LocalDate.of(2025, 11, 12))
        val s = store(old)
        val draft = old.copy(id = "fd2", invested = bd("107186"), startDate = LocalDate.of(2026, 11, 12))
        val u = s.renew("fd1", draft)!!
        assertEquals(HoldingStatus.RENEWED, s.state.value.find("fd1")!!.status)
        assertEquals("fd1", s.state.value.find("fd2")!!.renewedFrom)
        // the renewed one no longer counts, the successor does
        val sum = Portfolio.summarize(s.state.value.holdings, null, today)
        assertEquals(listOf("fd2"), sum.rows.map { it.holding.id })
        s.undo(u)
        assertEquals(listOf(old), s.state.value.holdings)
    }

    @Test fun store_concurrentChanges_neverLoseOne() {
        val s = store()
        val threads = (0 until 8).map { t -> Thread { repeat(50) { i -> s.add(mf().copy(id = "h$t-$i")) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(400, s.state.value.holdings.size)
    }

    // ---- net worth: migration, no double counting

    private fun item(id: String, kind: NetWorthKind, amount: String) = NetWorthItem(id, "Item $id", kind, bd(amount), today.minusDays(10))

    @Test fun migration_isIdempotent_andTotalsMatch() {
        val items = listOf(
            item("a", NetWorthKind.FIXED_DEPOSIT, "100000"), item("b", NetWorthKind.MUTUAL_FUNDS, "250000"),
            item("c", NetWorthKind.STOCKS, "40000"), item("d", NetWorthKind.GOLD, "75000"), item("e", NetWorthKind.RETIREMENT, "300000"),
            item("f", NetWorthKind.CASH, "5000"), item("g", NetWorthKind.PROPERTY, "9000000"), item("h", NetWorthKind.OTHER_DEBT, "20000")
        )
        val before = NetWorth.build(items, emptyList(), emptyList(), today)
        val s = store()
        assertEquals(5, s.migrate(items))
        assertEquals(0, s.migrate(items))
        assertEquals(5, s.state.value.holdings.size)
        val st = s.state.value
        val after = NetWorth.build(items, emptyList(), emptyList(), today, st.holdings, st.migratedItemIds)
        assertEquals(before.totalAssets, after.totalAssets)
        assertEquals(before.totalLiabilities, after.totalLiabilities)
        assertEquals(before.netWorth, after.netWorth)
        assertEquals(before.assets.size, after.assets.size)
        // the portfolio total equals the investment part of net worth
        val portfolio = Portfolio.summarize(st.holdings, null, today).totalCurrent
        assertEquals(bd("765000"), portfolio)
    }

    @Test fun migration_survivesRemovalOfAHolding_withoutComingBack() {
        val items = listOf(item("a", NetWorthKind.GOLD, "1000"))
        val s = store()
        s.migrate(items)
        s.remove("nw:a")
        s.migrate(items)
        assertTrue(s.state.value.holdings.isEmpty())
        val st = s.state.value
        assertEquals(BigDecimal.ZERO, NetWorth.build(items, emptyList(), emptyList(), today, st.holdings, st.migratedItemIds).totalAssets)
    }

    @Test fun networth_usesHoldingValues_andClosedDoNotCount() {
        val hs = listOf(fd(), mf(), fd(id = "x", status = HoldingStatus.CLOSED))
        val r = NetWorth.build(emptyList(), emptyList(), emptyList(), today, hs)
        assertEquals(2, r.assets.size)
        assertEquals(Portfolio.summarize(hs, null, today).totalCurrent, r.totalAssets)
        assertTrue(r.assets.all { it.holdingId != null })
    }

    @Test fun calendarAndAgenda_maturities() {
        val h = fd(start = LocalDate.of(2025, 11, 12))
        assertEquals(listOf("fd1"), Portfolio.maturingSoon(listOf(h), today).map { it.holding.id })
        assertTrue(Portfolio.maturingSoon(listOf(h), today, 5).isEmpty())
        assertTrue(Portfolio.maturingSoon(listOf(h.copy(status = HoldingStatus.CLOSED)), today).isEmpty())
    }
}
