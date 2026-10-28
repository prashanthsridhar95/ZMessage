package com.zyke.message.domain

import com.zyke.message.domain.accounts.StatementDays
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatementDaysTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test
    fun billsOnTheSameDay_giveThatDay() {
        assertEquals(12, StatementDays.suggest(listOf(d(8, 12), d(9, 12), d(10, 12))))
    }

    @Test
    fun billsAFewDaysApart_giveTheEarliest() {
        assertEquals(12, StatementDays.suggest(listOf(d(8, 13), d(9, 12), d(10, 14))))
    }

    @Test
    fun aLoneBill_isTakenAsItIs() {
        assertEquals(5, StatementDays.suggest(listOf(d(10, 5))))
    }

    @Test
    fun scatteredBills_giveNothing() {
        assertNull(StatementDays.suggest(listOf(d(8, 3), d(9, 17))))
    }

    @Test
    fun noBills_giveNothing() {
        assertNull(StatementDays.suggest(emptyList()))
    }
}
