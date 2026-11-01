package com.zyke.message.domain

import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.privacy.AmountMasker
import com.zyke.message.domain.privacy.RevealTimer
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class AmountMaskerTest {
    private val h = AmountMasker.PLACEHOLDER

    @Test fun masksIndianGroupedAmount() = assertEquals("Paid $h today", AmountMasker.mask("Paid ₹1,23,456.78 today"))
    @Test fun masksSingleDigit() = assertEquals(h, AmountMasker.mask("₹5"))
    @Test fun masksSpaceAfterSign() = assertEquals("Bal $h", AmountMasker.mask("Bal ₹ 500"))
    @Test fun masksRsDot() = assertEquals("Paid $h", AmountMasker.mask("Paid Rs. 500"))
    @Test fun masksRsForms() {
        assertEquals("debited by $h on", AmountMasker.mask("debited by Rs.500.00 on"))
        assertEquals("debited by $h on", AmountMasker.mask("debited by Rs 1,500 on"))
        assertEquals("debited by $h on", AmountMasker.mask("debited by rs500 on"))
    }
    @Test fun masksInr() {
        assertEquals("Avl Bal $h.", AmountMasker.mask("Avl Bal INR 12,000.50."))
        assertEquals("Avl Bal $h", AmountMasker.mask("Avl Bal INR500"))
    }
    @Test fun masksSeveralInOneSentence() =
        assertEquals("You are $h below last month ($h vs $h)", AmountMasker.mask("You are ₹1,200 below last month (₹3,400 vs ₹4,600)"))
    @Test fun keepsTrailingPunctuation() {
        assertEquals("Paid $h.", AmountMasker.mask("Paid ₹500."))
        assertEquals("$h, then $h", AmountMasker.mask("₹1,200, then ₹30"))
        assertEquals("$h/mo", AmountMasker.mask("₹499/mo"))
    }
    @Test fun keepsSignOutside() = assertEquals("-$h", AmountMasker.mask("-₹500"))
    @Test fun neverMasksOrdinaryNumbers() {
        listOf(
            "12 Mar 2025", "3 messages", "45% used", "card ending 5765", "1,234 points", "Due in 5 days", "A/c XX1234 on 05-03-25",
            "Rs", "Rsvp 500", "Mars 500", "Tours 500", "INRIA 500", "Bars. 200", "₹ ••••", "₹", "₹ abc", "price: 500"
        ).forEach { assertEquals(it, it, AmountMasker.mask(it)) }
    }
    @Test fun unchangedInstanceWhenNothingToMask() {
        val s = "Nothing here, 12 items"
        assertSame(s, AmountMasker.mask(s))
    }
    @Test fun alreadyMaskedIsStable() = assertEquals(h, AmountMasker.mask(h))
    @Test fun spokenForm() = assertEquals("Paid amount hidden to Burmy", AmountMasker.maskSpoken("Paid ₹540 to Burmy"))
    @Test fun findReturnsPositions() {
        val spans = AmountMasker.find("ab ₹1,000 cd Rs.5")
        assertEquals(listOf(3..8, 13..16), spans)
    }
    @Test fun containsAmount() {
        assertTrue(AmountMasker.containsAmount("x ₹1"))
        assertFalse(AmountMasker.containsAmount("x 1"))
    }
    @Test fun emptyAndEdges() {
        assertEquals("", AmountMasker.mask(""))
        assertEquals("₹", AmountMasker.mask("₹"))
        assertEquals("a $h", AmountMasker.mask("a ₹9"))
        assertEquals("R", AmountMasker.mask("R"))
        assertEquals("IN", AmountMasker.mask("IN"))
    }
    @Test fun decimalWithoutLeadingDigitIsNotAmount() = assertEquals("₹.50", AmountMasker.mask("₹.50"))
}

class RevealTimerTest {
    private var now = 1_000L
    private val timer = RevealTimer({ now })

    @Test fun startsHidden() { assertFalse(timer.isRevealed()); assertEquals(0L, timer.remainingMillis()) }

    @Test fun authenticatedWindowIsSixtySeconds() {
        timer.grant(authenticated = true)
        now += 59_999
        assertTrue(timer.isRevealed())
        now += 1
        assertFalse(timer.isRevealed())
    }

    @Test fun tapWindowIsFiveSeconds() {
        timer.grant(authenticated = false)
        now += 4_999
        assertTrue(timer.isRevealed())
        now += 1
        assertFalse(timer.isRevealed())
    }

    @Test fun hideNowEndsTheWindow() {
        timer.grant(true)
        timer.hide()
        assertFalse(timer.isRevealed())
    }

    @Test fun grantingAgainRestartsTheWindow() {
        timer.grant(true)
        now += 50_000
        timer.grant(true)
        assertEquals(60_000L, timer.remainingMillis())
    }

    @Test fun labelRoundsUp() {
        assertEquals("0:45", RevealTimer.label(44_200))
        assertEquals("1:00", RevealTimer.label(60_000))
        assertEquals("0:01", RevealTimer.label(1))
        assertEquals("0:00", RevealTimer.label(0))
        assertEquals("0:00", RevealTimer.label(-5))
    }

    @Test fun settingsDefaults() {
        val s = AppSettings()
        assertFalse(s.hideAmounts)
        assertTrue(s.authForAmounts)
    }
}
