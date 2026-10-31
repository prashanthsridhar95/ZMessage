package com.zyke.message.domain

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.envelopes.CarryOver
import com.zyke.message.domain.envelopes.EnvelopeBook
import com.zyke.message.domain.envelopes.Envelopes
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

class EnvelopesTest {
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

    private fun book(vararg pairs: Pair<MessageCategory, String>, start: LocalDate = oct): EnvelopeBook =
        pairs.fold(EnvelopeBook()) { b, (c, a) -> Envelopes.assign(b, start, c, bd(a)) }

    private fun status(b: EnvelopeBook, msgs: List<ClassifiedMessage>, today: LocalDate, spendable: String? = "20000") =
        Envelopes.status(b, msgs, rule, today, zone, spendable?.let(::bd))

    @Test fun spentHonoursSetAsideAndOnlyThisCycle() {
        val msgs = listOf(
            spend(MessageCategory.FOOD, "1000", oct.plusDays(2)),
            spend(MessageCategory.FOOD, "500", oct.plusDays(3), SetAside.EXCLUDED),
            spend(MessageCategory.FOOD, "700", oct.plusDays(4), SetAside.TRANSFER),
            spend(MessageCategory.FOOD, "300", oct.plusDays(5), received = true),
            spend(MessageCategory.FOOD, "900", oct.minusDays(2)),
            spend(MessageCategory.FUEL, "200", oct.plusDays(2))
        )
        val s = status(book(MessageCategory.FOOD to "8000", MessageCategory.FUEL to "3000"), msgs, oct.plusDays(10))
        val food = s.lines.first { it.category == MessageCategory.FOOD }
        eq("1000", food.spent)
        eq("7000", food.left)
        eq("11000", s.assignedTotal)
        eq("9000", s.unassigned)
    }

    @Test fun noPlanMeansNoUnassigned() {
        assertNull(status(book(MessageCategory.FOOD to "100"), emptyList(), oct, spendable = null).unassigned)
    }

    @Test fun overspentShowsNegativeLeft() {
        val s = status(book(MessageCategory.FOOD to "500"), listOf(spend(MessageCategory.FOOD, "800", oct.plusDays(1))), oct.plusDays(2))
        val line = s.lines.single()
        eq("-300", line.left)
        assertTrue(line.isOver)
    }

    @Test fun moveMoneyAdjustsBothEnvelopes() {
        var b = book(MessageCategory.FOOD to "8000", MessageCategory.FUEL to "3000")
        val s = status(b, emptyList(), oct)
        b = Envelopes.move(b, s, MessageCategory.FOOD, MessageCategory.FUEL, bd("2000"))
        val after = status(b, emptyList(), oct)
        eq("6000", after.lines.first { it.category == MessageCategory.FOOD }.left)
        eq("5000", after.lines.first { it.category == MessageCategory.FUEL }.left)
        eq("11000", after.assignedTotal)
        // More than the source has left, the same envelope, or an unknown one: unchanged.
        assertEquals(b, Envelopes.move(b, after, MessageCategory.FOOD, MessageCategory.FUEL, bd("9000")))
        assertEquals(b, Envelopes.move(b, after, MessageCategory.FOOD, MessageCategory.FOOD, bd("1")))
        assertEquals(b, Envelopes.move(b, after, MessageCategory.FOOD, MessageCategory.HEALTH, bd("1")))
        val undone = status(Envelopes.undoMove(b, oct), emptyList(), oct)
        eq("8000", undone.lines.first { it.category == MessageCategory.FOOD }.left)
    }

    @Test fun carryOverAcrossTwoCycles() {
        var b = book(MessageCategory.FOOD to "1000", MessageCategory.FUEL to "1000")
        b = Envelopes.assign(b, nov, MessageCategory.FOOD, bd("1000"))
        b = Envelopes.assign(b, nov, MessageCategory.FUEL, bd("1000"))
        b = Envelopes.setCarry(b, MessageCategory.FOOD, CarryOver.CARRY)
        val msgs = listOf(spend(MessageCategory.FOOD, "400", oct.plusDays(3)), spend(MessageCategory.FUEL, "400", oct.plusDays(3)))
        val s = status(b, msgs, nov.plusDays(1))
        eq("1600", s.lines.first { it.category == MessageCategory.FOOD }.left)
        eq("1000", s.lines.first { it.category == MessageCategory.FUEL }.left)
    }

    @Test fun overspendIsNotCarriedAsDebtAndLeftoversChain() {
        val dec = LocalDate.of(2026, 12, 1)
        var b = EnvelopeBook()
        listOf(oct, nov, dec).forEach { b = Envelopes.assign(b, it, MessageCategory.FOOD, bd("1000")) }
        b = Envelopes.setCarry(b, MessageCategory.FOOD, CarryOver.CARRY)
        val msgs = listOf(spend(MessageCategory.FOOD, "400", oct.plusDays(1)), spend(MessageCategory.FOOD, "1500", nov.plusDays(1)))
        // Oct leaves 600; Nov has 1600, spends 1500: 100 carried to Dec.
        eq("1100", status(b, msgs, dec.plusDays(1)).lines.single().left)
        val b2 = Envelopes.assign(b, nov, MessageCategory.FOOD, bd("500"))
        // Nov has 1100, spends 1500: overspent, nothing carried.
        eq("1000", status(b2, msgs, dec.plusDays(1)).lines.single().left)
    }

    @Test fun startFromCopiesOnlyIntoAnEmptyCycle() {
        val b = book(MessageCategory.FOOD to "100")
        val next = Envelopes.startFrom(b, nov, oct)
        eq("100", next.cycles.getValue(nov).assigned.getValue(MessageCategory.FOOD))
        assertEquals(next, Envelopes.startFrom(next, nov, oct))
    }
}
