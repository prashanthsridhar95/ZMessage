package com.zyke.message.domain

import com.zyke.message.domain.cards.CardCycleBuilder
import com.zyke.message.domain.cards.CardMatcher
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardCyclesTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneOffset.UTC
    private val card = UserCard("c1", CardType.CREDIT, "1234", "HDFC Regalia", statementDay = 15)

    private fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun spend(id: Long, date: LocalDate, amount: String, last4: String = "1234") = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank Card $last4\nTo SWIGGY\nOn 26/10/26\nRef $id", millis(date), true)
    )

    @Test
    fun cycleBoundaries_followTheStatementDate() {
        assertEquals(LocalDate.of(2026, 10, 15), CardCycleBuilder.cycleEnd(LocalDate.of(2026, 10, 15), 15))
        assertEquals(LocalDate.of(2026, 11, 15), CardCycleBuilder.cycleEnd(LocalDate.of(2026, 10, 16), 15))
        assertEquals(LocalDate.of(2026, 9, 16), CardCycleBuilder.cycleStart(LocalDate.of(2026, 10, 15), 15))
        // A 31st statement date falls back to the last day of shorter months.
        assertEquals(LocalDate.of(2026, 2, 28), CardCycleBuilder.cycleEnd(LocalDate.of(2026, 2, 10), 31))
        // No statement date: calendar months.
        assertEquals(LocalDate.of(2026, 10, 31), CardCycleBuilder.cycleEnd(LocalDate.of(2026, 10, 5), null))
    }

    @Test
    fun spendsAreMatchedToTheCardAndGroupedByCycle() {
        val messages = listOf(
            spend(1, LocalDate.of(2026, 10, 20), "500.00"),
            spend(2, LocalDate.of(2026, 10, 10), "300.00"),
            spend(3, LocalDate.of(2026, 9, 20), "200.00"),
            spend(4, LocalDate.of(2026, 10, 12), "999.00", last4 = "9999")
        ).map { CardMatcher.apply(it, listOf(card)) }

        assertNull(messages[3].cardId)
        val cycles = CardCycleBuilder.build(messages, listOf(card), Instant.ofEpochMilli(millis(LocalDate.of(2026, 10, 25))), zone)
            .single().cycles

        // Today is 25 Oct: the current cycle is 16 Oct - 15 Nov.
        assertEquals(LocalDate.of(2026, 10, 16), cycles[0].start)
        assertEquals(0, BigDecimal("500").compareTo(cycles[0].spent))
        assertEquals(0, BigDecimal("500").compareTo(cycles[1].spent))
        assertEquals(2, cycles[1].spendCount)
        assertEquals(0, BigDecimal.ZERO.compareTo(cycles[2].spent))
        assertEquals(CardCycleBuilder.CYCLES_SHOWN, cycles.size)
    }

    @Test
    fun accountsAreMatchedByTheirLastDigits_andKeptApartFromCardsWithTheSameDigits() {
        val account = UserCard("a1", CardType.ACCOUNT, "5765", "Salary", bankName = "HDFC Bank")
        val sameDigitsCard = UserCard("c2", CardType.CREDIT, "5765", "Other card")
        val debit = classifier.classify(
            SmsMessage(1, "AD-HDFCBK", "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1", 1_700_000_000_000L, true)
        )

        assertEquals("a1", CardMatcher.match(debit, listOf(sameDigitsCard, account))?.id)
        assertNull(CardMatcher.match(debit, listOf(sameDigitsCard)))
        assertEquals("Salary A/c •• 5765", account.label)
    }
}
