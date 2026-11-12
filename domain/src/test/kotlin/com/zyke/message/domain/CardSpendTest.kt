package com.zyke.message.domain

import com.zyke.message.domain.cards.CardLimits
import com.zyke.message.domain.cards.CardSpend
import com.zyke.message.domain.cards.CategoryShare
import com.zyke.message.domain.cards.DeckModel
import com.zyke.message.domain.cards.InMemoryCardLimits
import com.zyke.message.domain.cards.NoCardLimits
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSpendTest {
    private fun e(c: MessageCategory, a: Int) = c to BigDecimal(a)

    @Test fun top4PlusOther_percentsAddTo100() {
        val rows = CardSpend.breakdown(
            listOf(
                e(MessageCategory.SHOPPING, 4200), e(MessageCategory.FOOD, 2600), e(MessageCategory.TRAVEL, 1800),
                e(MessageCategory.FUEL, 700), e(MessageCategory.UTILITIES, 400), e(MessageCategory.HEALTH, 300)
            )
        )
        assertEquals(5, rows.size)
        assertNull(rows.last().category)
        assertEquals(BigDecimal(700), rows.last().amount)
        assertEquals(100, rows.sumOf { it.percent })
        assertEquals(MessageCategory.SHOPPING, rows.first().category)
    }

    @Test fun fiveCategoriesShowAllFive_noPointInAnOtherOfOne() {
        val rows = CardSpend.breakdown(MessageCategory.values().take(5).map { e(it, 100) })
        assertEquals(5, rows.size)
        assertTrue(rows.none { it.category == null })
        assertEquals(100, rows.sumOf { it.percent })
    }

    @Test fun sameCategoryIsSummed_refundsOffsetAndNothingSpentIsEmpty() {
        val rows = CardSpend.breakdown(listOf(e(MessageCategory.FOOD, 500), e(MessageCategory.FOOD, 500), e(MessageCategory.SHOPPING, 300), e(MessageCategory.SHOPPING, -300)))
        assertEquals(listOf(MessageCategory.FOOD), rows.map { it.category })
        assertEquals(100, rows.single().percent)
        assertTrue(CardSpend.breakdown(emptyList()).isEmpty())
    }

    @Test fun thirdsRoundToExactly100() {
        val rows = CardSpend.breakdown(listOf(e(MessageCategory.FOOD, 1), e(MessageCategory.SHOPPING, 1), e(MessageCategory.TRAVEL, 1)))
        assertEquals(100, rows.sumOf { it.percent })
        assertTrue(rows.all { it.percent in 33..34 })
    }

    @Test fun segmentsKeepASmallOneVisibleAndAddUpToOne() {
        val seg = CardSpend.segments(listOf(CategoryShare(MessageCategory.FOOD, BigDecimal.TEN, 99), CategoryShare(null, BigDecimal.ONE, 1)))
        assertEquals(1f, seg.sum(), 0.0001f)
        assertTrue(seg[1] >= 0.045f)
        assertTrue(CardSpend.segments(emptyList()).isEmpty())
    }

    @Test fun limitMath() {
        val spent = BigDecimal("39540.50")
        assertEquals(0.2636f, CardLimits.progress(spent, 150_000), 0.001f)
        assertEquals(BigDecimal("110459.50"), CardLimits.left(spent, 150_000))
        assertEquals(1f, CardLimits.progress(BigDecimal(200_000), 150_000), 0f)
        assertEquals(0f, CardLimits.progress(spent, null), 0f)
        assertNull(CardLimits.left(spent, null))
        assertTrue(CardLimits.left(BigDecimal(160_000), 150_000)!!.signum() < 0)
    }

    @Test fun statusSteps() {
        val l = 100_000L
        assertEquals(CardLimits.Status.NONE, CardLimits.status(BigDecimal.TEN, null))
        assertEquals(CardLimits.Status.OK, CardLimits.status(BigDecimal(79_999), l))
        assertEquals(CardLimits.Status.NEAR, CardLimits.status(BigDecimal(80_000), l))
        assertEquals(CardLimits.Status.NEAR, CardLimits.status(BigDecimal(100_000), l))
        assertEquals(CardLimits.Status.OVER, CardLimits.status(BigDecimal(100_001), l))
    }

    @Test fun resetsInDaysCountsToday_andIsSilentWhenUnknown() {
        val end = LocalDate.of(2026, 11, 30)
        assertEquals(20, CardLimits.resetsInDays(LocalDate.of(2026, 11, 11), end, true))
        assertEquals(1, CardLimits.resetsInDays(end, end, true))
        assertNull(CardLimits.resetsInDays(LocalDate.of(2026, 11, 11), end, false))
        assertNull(CardLimits.resetsInDays(end.plusDays(1), end, true))
    }

    @Test fun sliderSnapsAndStaysInRange() {
        assertEquals(CardLimits.MIN, CardLimits.snap(1))
        assertEquals(50_000L, CardLimits.snap(50_400))
        assertEquals(CardLimits.MAX, CardLimits.snap(99_999_999))
        assertEquals(0f, CardLimits.fraction(CardLimits.MIN), 0f)
        assertEquals(1f, CardLimits.fraction(CardLimits.MAX), 0f)
        assertEquals(CardLimits.MIN, CardLimits.fromFraction(-1f))
        assertEquals(CardLimits.MAX, CardLimits.fromFraction(2f))
        val v = 250_000L
        assertEquals(v, CardLimits.fromFraction(CardLimits.fraction(v)))
    }

    @Test fun chipsAddTheCreditLimitOnlyWhenItIsKnownAndInRange() {
        assertEquals(CardLimits.PRESETS, CardLimits.presets(null))
        assertEquals(CardLimits.PRESETS, CardLimits.presets(50_000))
        assertTrue(CardLimits.presets(3_00_000).contains(3_00_000L))
        assertEquals(CardLimits.presets(3_00_000).sorted(), CardLimits.presets(3_00_000))
        assertEquals(CardLimits.PRESETS, CardLimits.presets(99_999_999))
    }

    @Test fun deckLayersAndDots() {
        assertEquals(0, DeckModel.layersBehind(0)); assertEquals(0, DeckModel.layersBehind(1))
        assertEquals(1, DeckModel.layersBehind(2)); assertEquals(2, DeckModel.layersBehind(9))
        assertTrue(DeckModel.dots(1, 0).isEmpty())
        assertEquals(listOf(true, false, false), DeckModel.dots(3, 0).map { it.on })
        val many = DeckModel.dots(20, 10)
        assertEquals(DeckModel.MAX_DOTS, many.size)
        assertEquals(10, many.single { it.on }.index)
        assertEquals(0, DeckModel.dots(20, 0).first().index)
        assertEquals(19, DeckModel.dots(20, 19).last().index)
        assertEquals(19, DeckModel.clamp(50, 20)); assertEquals(0, DeckModel.clamp(-3, 20)); assertEquals(0, DeckModel.clamp(3, 0))
    }

    @Test fun aSwipeCommitsOnDistanceOrOnAFlickTheSameWay() {
        // Past 30 percent of the width, either way.
        assertTrue(DeckModel.commits(-301f, 1000f, 0f, 3))
        assertTrue(DeckModel.commits(301f, 1000f, 0f, 3))
        assertFalse(DeckModel.commits(-299f, 1000f, 0f, 3))
        // A fast flick in the direction of the drag commits at any distance; one against it does not.
        assertTrue(DeckModel.commits(-40f, 1000f, -1_500f, 3))
        assertFalse(DeckModel.commits(-40f, 1000f, 1_500f, 3))
        assertFalse(DeckModel.commits(-40f, 1000f, -1_000f, 3))
        // One card (or none) has nowhere to go; no drag is no commit; a zero width does not divide by zero.
        assertFalse(DeckModel.commits(-900f, 1000f, -5_000f, 1))
        assertFalse(DeckModel.commits(-900f, 1000f, -5_000f, 0))
        assertFalse(DeckModel.commits(0f, 1000f, -5_000f, 3))
        assertTrue(DeckModel.commits(-5f, 0f, 0f, 2))
    }

    @Test fun limitsAreKeptPerCardInMemory() {
        val s = InMemoryCardLimits()
        s.set("credit_card-5580", 150_000)
        s.set("credit_card-5580", 5)
        assertEquals(CardLimits.MIN, s.limits.value["credit_card-5580"])
        s.clear("credit_card-5580")
        assertTrue(s.limits.value.isEmpty())
        NoCardLimits.set("x", 5_000)
        assertTrue(NoCardLimits.limits.value.isEmpty())
    }

    private val classifier = MessageClassifier()
    private fun swipe(id: Long, day: LocalDate, amount: String, last4: String) = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank Card $last4\nTo SWIGGY\nOn 26/10/26\nRef $id", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true)
    )

    @Test fun viewSumsOnlyThisCardsSpendsInTheCurrentCycle() {
        val today = LocalDate.of(2026, 11, 11)
        val view = CardSpend.view(
            listOf(
                swipe(1, LocalDate.of(2026, 11, 3), "500.00", "1234"),
                swipe(2, LocalDate.of(2026, 11, 9), "250.50", "1234"),
                swipe(3, LocalDate.of(2026, 9, 9), "999.00", "1234"),
                swipe(4, LocalDate.of(2026, 11, 5), "777.00", "9999")
            ),
            "1234", null, today, ZoneOffset.UTC
        )
        assertEquals(BigDecimal("750.50"), view.spent)
        assertEquals(LocalDate.of(2026, 11, 30), view.cycleEnd)
        assertEquals(false, view.cycleKnown)
        assertEquals("recent spends are not limited to the cycle", listOf(2L, 1L, 3L), view.recent.map { it.message.id })
        assertEquals(100, view.shares.sumOf { it.percent })
        val withDay = CardSpend.view(emptyList(), "1234", 15, today, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 11, 15), withDay.cycleEnd)
        assertEquals(true, withDay.cycleKnown)
        assertEquals(BigDecimal.ZERO, withDay.spent)
        assertTrue(withDay.shares.isEmpty() && withDay.recent.isEmpty())
    }
}
