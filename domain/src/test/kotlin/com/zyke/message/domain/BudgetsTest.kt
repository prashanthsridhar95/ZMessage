package com.zyke.message.domain

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.BudgetAdjustments
import com.zyke.message.domain.dashboard.BudgetMigration
import com.zyke.message.domain.dashboard.BudgetMove
import com.zyke.message.domain.dashboard.BudgetStatus
import com.zyke.message.domain.dashboard.Budgets
import com.zyke.message.domain.dashboard.CarryOver
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetsTest {
    private val zone = ZoneOffset.UTC
    private val rule = CycleRule.CALENDAR
    private val oct = LocalDate.of(2026, 10, 1)
    private val nov = LocalDate.of(2026, 11, 1)
    private var nextId = 1L

    private fun spend(category: MessageCategory, amount: String, date: LocalDate, setAside: SetAside? = null, received: Boolean = false) =
        ClassifiedMessage(
            message = SmsMessage(nextId++, "BANK", "x", date.atStartOfDay(zone).toInstant().toEpochMilli() + 1000, true),
            category = category,
            merchant = Merchant("M", MerchantLogo.PERSON, category),
            content = MessageContent.Transaction(BankName.OTHERS, "Shop", amount, "1234", received, false, false),
            setAside = setAside
        )

    private fun bd(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, bd(expected).compareTo(actual))

    private fun limits(vararg pairs: Pair<MessageCategory, String>): Map<MessageCategory, BigDecimal> = pairs.associate { (c, a) -> c to bd(a) }

    private fun status(m: Map<MessageCategory, BigDecimal>, a: BudgetAdjustments, msgs: List<ClassifiedMessage>, today: LocalDate, spendable: String? = "20000"): BudgetStatus =
        Budgets.status(m, a, msgs, rule, today, zone, spendable?.let(::bd))

    private val none = BudgetAdjustments()

    @Test fun spentHonoursSetAsideAndOnlyThisCycle() {
        val msgs = listOf(
            spend(MessageCategory.FOOD, "1000", oct.plusDays(2)),
            spend(MessageCategory.FOOD, "500", oct.plusDays(3), SetAside.EXCLUDED),
            spend(MessageCategory.FOOD, "700", oct.plusDays(4), SetAside.TRANSFER),
            spend(MessageCategory.FOOD, "300", oct.plusDays(5), received = true),
            spend(MessageCategory.FOOD, "900", oct.minusDays(2)),
            spend(MessageCategory.FUEL, "200", oct.plusDays(2))
        )
        val s = status(limits(MessageCategory.FOOD to "8000", MessageCategory.FUEL to "3000"), none, msgs, oct.plusDays(10))
        val food = s.lines.first { it.category == MessageCategory.FOOD }
        eq("1000", food.spent)
        eq("7000", food.left)
        eq("11000", s.assignedTotal)
        eq("9000", s.unassigned)
    }

    @Test fun noPlanMeansNoUnassigned() {
        assertNull(status(limits(MessageCategory.FOOD to "100"), none, emptyList(), oct, spendable = null).unassigned)
    }

    @Test fun overspentShowsNegativeLeft() {
        val s = status(limits(MessageCategory.FOOD to "500"), none, listOf(spend(MessageCategory.FOOD, "800", oct.plusDays(1))), oct.plusDays(2))
        val line = s.lines.single()
        eq("-300", line.left)
        assertTrue(line.isOver)
    }

    @Test fun moveMoneyAdjustsBothBudgets() {
        val m = limits(MessageCategory.FOOD to "8000", MessageCategory.FUEL to "3000")
        val s = status(m, none, emptyList(), oct)
        val a = Budgets.move(none, s, MessageCategory.FOOD, MessageCategory.FUEL, bd("2000"))
        val after = status(m, a, emptyList(), oct)
        eq("6000", after.lines.first { it.category == MessageCategory.FOOD }.left)
        eq("5000", after.lines.first { it.category == MessageCategory.FUEL }.left)
        eq("11000", after.assignedTotal)
        // More than the source has left, the same budget, or one without a budget: unchanged.
        assertEquals(a, Budgets.move(a, after, MessageCategory.FOOD, MessageCategory.FUEL, bd("9000")))
        assertEquals(a, Budgets.move(a, after, MessageCategory.FOOD, MessageCategory.FOOD, bd("1")))
        assertEquals(a, Budgets.move(a, after, MessageCategory.FOOD, MessageCategory.HEALTH, bd("1")))
        val undone = status(m, Budgets.undoMove(a, oct), emptyList(), oct)
        eq("8000", undone.lines.first { it.category == MessageCategory.FOOD }.left)
    }

    @Test fun carryOverAcrossTwoCycles() {
        val m = limits(MessageCategory.FOOD to "1000", MessageCategory.FUEL to "1000")
        val a = Budgets.setCarry(none, MessageCategory.FOOD, CarryOver.CARRY, oct)
        val msgs = listOf(spend(MessageCategory.FOOD, "400", oct.plusDays(3)), spend(MessageCategory.FUEL, "400", oct.plusDays(3)))
        val s = status(m, a, msgs, nov.plusDays(1))
        eq("1600", s.lines.first { it.category == MessageCategory.FOOD }.left)
        eq("1000", s.lines.first { it.category == MessageCategory.FUEL }.left)
    }

    @Test fun carryOverStartsWhereItWasTurnedOnNotInThePast() {
        val m = limits(MessageCategory.FOOD to "1000")
        val a = Budgets.setCarry(none, MessageCategory.FOOD, CarryOver.CARRY, nov)
        // Nothing before November counts, however little was spent then.
        eq("1000", status(m, a, emptyList(), nov.plusDays(1)).lines.single().left)
        // Turning it on again while it is on keeps where it began.
        assertEquals(a, Budgets.setCarry(a, MessageCategory.FOOD, CarryOver.CARRY, LocalDate.of(2026, 12, 1)))
        assertTrue(Budgets.setCarry(a, MessageCategory.FOOD, CarryOver.RESET, nov).carryFrom.isEmpty())
    }

    @Test fun overspendIsNotCarriedAsDebtAndLeftoversChain() {
        val dec = LocalDate.of(2026, 12, 1)
        val m = limits(MessageCategory.FOOD to "1000")
        val a = Budgets.setCarry(none, MessageCategory.FOOD, CarryOver.CARRY, oct)
        val msgs = listOf(spend(MessageCategory.FOOD, "400", oct.plusDays(1)), spend(MessageCategory.FOOD, "1500", nov.plusDays(1)))
        // Oct leaves 600; Nov has 1600, spends 1500: 100 carried to Dec.
        eq("1100", status(m, a, msgs, dec.plusDays(1)).lines.single().left)
        // Nov spends more than it had: overspent, nothing carried.
        val msgs2 = listOf(spend(MessageCategory.FOOD, "400", oct.plusDays(1)), spend(MessageCategory.FOOD, "2000", nov.plusDays(1)))
        eq("1000", status(m, a, msgs2, dec.plusDays(1)).lines.single().left)
    }

    @Test fun limitsShowTheBudgetAsItStandsThisCycle() {
        val m = limits(MessageCategory.FOOD to "1000")
        val a = Budgets.setCarry(none, MessageCategory.FOOD, CarryOver.CARRY, oct)
        val msgs = listOf(spend(MessageCategory.FOOD, "400", oct.plusDays(1)), spend(MessageCategory.FOOD, "100", nov.plusDays(1)))
        val now = nov.plusDays(1).atStartOfDay(zone).toInstant().plusSeconds(3600)
        val entry = SpendingLimits.build(msgs, m, emptyMap(), now, zone, rule, a).single()
        eq("1600", entry.monthly!!.limit)
        eq("600", entry.carriedIn)
        eq("1500", entry.monthly!!.remaining)
        // Without adjustments it is the plain limit, as before.
        eq("1000", SpendingLimits.build(msgs, m, emptyMap(), now, zone, rule).single().monthly!!.limit)
    }

    @Test fun migrationTakesTheLatestCycleWithoutOverwritingBudgets() {
        val old = mapOf(
            oct to mapOf(MessageCategory.FOOD to bd("7000"), MessageCategory.FUEL to bd("500")),
            nov to mapOf(MessageCategory.FOOD to bd("8000"), MessageCategory.FUEL to bd("3000"))
        )
        val legacy = BudgetAdjustments(
            carry = mapOf(MessageCategory.FUEL to CarryOver.CARRY),
            moves = mapOf(nov to listOf(BudgetMove(MessageCategory.FOOD, MessageCategory.FUEL, bd("100"))))
        )
        val existing = limits(MessageCategory.FOOD to "9000")
        val r = BudgetMigration.fromEnvelopes(existing, none, old, legacy)
        eq("9000", r.monthly.getValue(MessageCategory.FOOD))
        eq("3000", r.monthly.getValue(MessageCategory.FUEL))
        assertEquals(CarryOver.CARRY, r.adjustments.carry[MessageCategory.FUEL])
        assertEquals(oct, r.adjustments.carryFrom[MessageCategory.FUEL])
        assertEquals(legacy.moves, r.adjustments.moves)
        // Running it again over its own result changes nothing.
        assertEquals(r, BudgetMigration.fromEnvelopes(r.monthly, r.adjustments, old, legacy))
        // Nothing to migrate leaves the budgets alone.
        assertEquals(BudgetMigration.Result(existing, none), BudgetMigration.fromEnvelopes(existing, none, emptyMap(), none))
    }
}
