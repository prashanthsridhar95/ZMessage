package com.zyke.message.presentation

import com.zyke.message.presentation.format.DateFormatters
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormattersTest {
    private val today = LocalDate.of(2025, 3, 5) // a Wednesday
    private val locale = Locale.ENGLISH

    @Test
    fun dayHeader_relativeBuckets() {
        assertEquals("Today, 05 Mar", DateFormatters.dayHeader(today, today, locale))
        assertEquals("Yesterday, 04 Mar", DateFormatters.dayHeader(today.minusDays(1), today, locale))
        assertEquals("Sunday, 02 Mar", DateFormatters.dayHeader(today.minusDays(3), today, locale))
        assertEquals("26 Feb", DateFormatters.dayHeader(today.minusDays(7), today, locale))
    }

    @Test
    fun detailTimestamp_includesTime() {
        val zone = ZoneId.of("UTC")
        val millis = ZonedDateTime.of(2025, 3, 5, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Today, 05 Mar, 02:30 PM", DateFormatters.detailTimestamp(millis, zone, today, locale))
        assertEquals("05 Mar, 02:30 PM", DateFormatters.detailTimestamp(millis, zone, today.plusDays(10), locale))
    }
}

class DisplayFormattersTest {
    private val today = LocalDate.of(2025, 3, 5)

    @org.junit.Test
    fun dueLabels() {
        val fmt = com.zyke.message.presentation.format.DisplayFormatters
        assertEquals("Due today", fmt.dueLabel(today, today, Locale.ENGLISH))
        assertEquals("Due tomorrow", fmt.dueLabel(today.plusDays(1), today, Locale.ENGLISH))
        assertEquals("Due in 5 days", fmt.dueLabel(today.plusDays(5), today, Locale.ENGLISH))
        assertEquals("Due 20 Mar", fmt.dueLabel(LocalDate.of(2025, 3, 20), today, Locale.ENGLISH))
        assertEquals(true, fmt.isUrgent(today.plusDays(3), today))
        assertEquals(false, fmt.isUrgent(today.plusDays(4), today))
    }

    @org.junit.Test
    fun rupeesAndTime() {
        val fmt = com.zyke.message.presentation.format.DisplayFormatters
        assertEquals("₹12345", fmt.rupees("12345").replace(",", ""))
        assertEquals("₹750.5", fmt.rupees(java.math.BigDecimal("750.50")).replace(",", ""))
        assertEquals("2:30 PM", fmt.time(
            ZonedDateTime.of(2025, 3, 5, 14, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(),
            ZoneId.of("UTC"), Locale.ENGLISH
        ))
    }
}
