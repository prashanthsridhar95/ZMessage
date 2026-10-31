package com.zyke.message.domain.accounts

import com.zyke.message.domain.insights.AccountBalance
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountInsightsTest {
    private val d = LocalDate.of(2026, 10, 31)
    private fun bal(day: Long, amount: Int, id: Long = day) = AccountBalance("1234", "HDFC", BigDecimal(amount), d.minusDays(day), id)

    @Test fun seriesIsOldestFirstOnePerDayAndCapped() {
        val history = listOf(bal(3, 100), bal(1, 300), bal(1, 350, id = 99), bal(2, 200))
        assertEquals(listOf(100, 200, 350), AccountInsights.balanceSeries(history).map { it.toInt() })
        assertEquals(2, AccountInsights.balanceSeries(history, 2).size)
    }

    @Test fun inShare() {
        assertNull(AccountInsights.inShare(BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals(0.25f, AccountInsights.inShare(BigDecimal(300), BigDecimal(100))!!, 0.0001f)
    }

    @Test fun utilisation() {
        assertNull(AccountInsights.utilisation(null, 1000))
        assertNull(AccountInsights.utilisation(BigDecimal(5), null))
        assertEquals(0.5f, AccountInsights.utilisation(BigDecimal(500), 1000)!!, 0.0001f)
        assertEquals(1f, AccountInsights.utilisation(BigDecimal(5000), 1000)!!, 0.0001f)
    }

    @Test fun lowAndStale() {
        assertTrue(AccountInsights.isLowBalance(BigDecimal(999)))
        assertTrue(!AccountInsights.isLowBalance(BigDecimal(1000)))
        assertTrue(!AccountInsights.isLowBalance(null))
        assertTrue(AccountInsights.isStale(d.minusDays(8), d))
        assertTrue(!AccountInsights.isStale(d.minusDays(7), d))
    }

    @Test fun age() {
        assertEquals("today", AccountInsights.age(d, d))
        assertEquals("yesterday", AccountInsights.age(d.minusDays(1), d))
        assertEquals("5 days ago", AccountInsights.age(d.minusDays(5), d))
        assertEquals("3 weeks ago", AccountInsights.age(d.minusDays(21), d))
        assertEquals("3 months ago", AccountInsights.age(d.minusDays(95), d))
    }
}
