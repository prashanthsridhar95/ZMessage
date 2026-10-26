package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingBuilderTest {
    private val classifier = MessageClassifier()
    private val today = LocalDate.of(2026, 10, 26)
    private var id = 1L

    private fun msg(body: String, daysAgo: Long = 0) = classifier.classify(
        SmsMessage(id++, "AD-HDFCBK", body, 1_793_000_000_000L - daysAgo * 86_400_000L, true)
    )

    @Test
    fun combinesBillsAndAutoDebits_soonestFirst_dedupingRepeats() {
        val alert = "Alert:\nINR.650.00 will be debited on 28/10/2026 from HDFC Bank Card 0421 for Google Play - Conten..\nID:YDJFTx5b52"
        val items = UpcomingBuilder.build(
            listOf(
                msg(alert),
                msg(alert, daysAgo = 1), // same alert again
                msg("HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:30-10-2026 pay https://hdfcbk.io/x"),
                msg("HDFC Bank Credit Card XX9999 Statement: Total due amt: Rs.1,000.00 Min due amt: Rs.100.00 Due by:01-10-2026 pay https://hdfcbk.io/y"), // already past
                msg("Alert:\nINR.99.00 will be debited on 15/12/2026 from HDFC Bank Card 0421 for Spotify - Conten..") // beyond 30 days
            ),
            today
        )

        assertEquals(listOf(UpcomingKind.AUTO_DEBIT, UpcomingKind.CARD_BILL), items.map { it.kind })
        assertEquals("Google Play", items[0].title)
        assertEquals(LocalDate.of(2026, 10, 28), items[0].date)
        assertEquals("HDFC Card •• 1234", items[1].title)
        assertEquals("https://hdfcbk.io/x", items[1].paymentLink)
    }
}
