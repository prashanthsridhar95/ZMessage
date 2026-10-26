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
