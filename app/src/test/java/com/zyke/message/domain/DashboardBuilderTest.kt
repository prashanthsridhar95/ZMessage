package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.dashboard.TrendUnit
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardBuilderTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2025, 3, 15, 12, 0, 0, 0, zone).toInstant() // Saturday
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun sent(amount: String, to: String, daysAgo: Long, account: String = "1234", card: Boolean = false): ClassifiedMessage {
        val from = if (card) "HDFC Bank Card $account" else "HDFC Bank A/C x$account"
        val body = "Sent Rs.$amount\nFrom $from\nTo $to\nOn 12/01/25\nRef 123456"
        return classifier.classify(SmsMessage(nextId++, "AD-HDFCBK", body, now.minusSeconds(daysAgo * 86_400).toEpochMilli(), true))
    }

    private fun received(amount: String, daysAgo: Long) = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Credit Alert!\nRs.$amount credited to HDFC Bank A/c xx9876 on 01-02-25 from VPA salary@okaxis (UPI 123456789)",
            now.minusSeconds(daysAgo * 86_400).toEpochMilli(), true
        )
    )

    private fun assertAmount(expected: String, actual: java.math.BigDecimal) =
        assertEquals(0, java.math.BigDecimal(expected).compareTo(actual))

    private fun build(messages: List<ClassifiedMessage>, query: DashboardQuery = DashboardQuery()) =
        DashboardBuilder.build(messages, query, now, zone)

    @Test
    fun totalsAndBreakdowns_forThisMonth() {
        val messages = listOf(
            sent("500.00", "friend@ybl", daysAgo = 1),
            sent("250.50", "friend@ybl", daysAgo = 3),
            sent("800.00", "swiggy@ybl", daysAgo = 5),
            received("9000.00", daysAgo = 2),
            sent("100.00", "friend@ybl", daysAgo = 40) // last month: excluded
        )

        val d = build(messages)

        assertAmount("1550.50", d.total)
        assertEquals(3, d.count)
        assertAmount("9000.00", d.receivedTotal)
        assertEquals(LocalDate.of(2025, 3, 1), d.range.start)

        assertEquals(listOf("swiggy", "friend@ybl"), d.vendors.map { it.key }) // by total desc
        val friend = d.vendors.first { it.key == "friend@ybl" }
        assertEquals(2, friend.count)
        assertAmount("750.50", friend.total)
        assertEquals(listOf(500.0, 250.5), friend.recent.map { it.amount.toDouble() }) // newest first
        assertEquals("HDFC A/C •• 1234", d.sources.single().label)
        assertEquals(setOf(MessageCategory.UPI_PAYMENT, MessageCategory.FOOD), d.categories.map { it.category }.toSet())
    }

    @Test
    fun receivedDirection_showsIncomeOnly() {
        val d = build(
            listOf(sent("500.00", "friend@ybl", 1), received("9000.00", 2)),
            DashboardQuery(direction = FlowDirection.RECEIVED)
        )
        assertAmount("9000.00", d.total)
        assertEquals("salary@okaxis", d.vendors.single().merchant.name)
    }

    @Test
    fun categoryFilter_narrowsVendorsButKeepsCategoryList() {
        val messages = listOf(sent("500.00", "friend@ybl", 1), sent("800.00", "swiggy@ybl", 2))
        val d = build(messages, DashboardQuery(category = MessageCategory.FOOD))

        assertAmount("800.00", d.total)
        assertEquals(1, d.vendors.size)
        assertEquals(2, d.categories.size)
    }

    @Test
    fun comparison_usesSameNumberOfDaysOfLastMonth() {
        val messages = listOf(
            sent("1000.00", "friend@ybl", daysAgo = 1),   // 14 Mar
            sent("500.00", "friend@ybl", daysAgo = 20),   // 23 Feb: outside 1..15 Feb window
            sent("400.00", "friend@ybl", daysAgo = 35)    // 8 Feb: inside
        )
        val d = build(messages)
        assertAmount("400.00", d.previousTotal!!)
        assertEquals(150.0, d.changePercent!!, 0.001)
    }

    @Test
    fun lastMonthAndAllTime() {
        val messages = listOf(sent("500.00", "friend@ybl", daysAgo = 20), sent("100.00", "friend@ybl", daysAgo = 100))

        val last = build(messages, DashboardQuery(period = DashboardPeriod.LAST_MONTH))
        assertAmount("500.00", last.total)
        assertEquals(LocalDate.of(2025, 2, 28), last.range.endInclusive)

        val all = build(messages, DashboardQuery(period = DashboardPeriod.ALL_TIME))
        assertAmount("600.00", all.total)
        assertNull(all.previousTotal)
        assertEquals(TrendUnit.MONTH, all.trendUnit)
    }

    @Test
    fun trend_isOneBarPerDayWithZeroes() {
        val d = build(listOf(sent("500.00", "friend@ybl", daysAgo = 1)))
        assertEquals(TrendUnit.DAY, d.trendUnit)
        assertEquals(15, d.trend.size)
        assertAmount("500.00", d.trend[13].amount) // 14 Mar
        assertEquals(0, d.trend[0].amount.signum())
    }

    @Test
    fun cardAndAccountSources_areSeparated() {
        val d = build(listOf(sent("500.00", "friend@ybl", 1), sent("300.00", "cafe@ybl", 2, account = "5555", card = true)))
        assertEquals(setOf("HDFC A/C •• 1234", "HDFC Card •• 5555"), d.sources.map { it.label }.toSet())
    }

    @Test
    fun emptyInbox_isEmpty() {
        val d = build(emptyList())
        assertTrue(d.isEmpty)
        assertNull(d.changePercent)
    }

    @Test
    fun trendBars_areSplitByCategory() {
        val d = build(listOf(sent("500.00", "friend@ybl", 1), sent("800.00", "swiggy@ybl", 1)))
        val bar = d.trend[13] // 14 Mar
        assertAmount("1300", bar.amount)
        assertEquals(listOf(MessageCategory.FOOD, MessageCategory.UPI_PAYMENT), bar.byCategory.map { it.first })
    }

    @Test
    fun budgets_attachToThisMonthSpendingOnly() {
        val messages = listOf(sent("800.00", "swiggy@ybl", 1), sent("500.00", "friend@ybl", 2))
        val budgets = mapOf(
            MessageCategory.FOOD to java.math.BigDecimal("1000"),
            MessageCategory.SHOPPING to java.math.BigDecimal("2000") // nothing spent yet
        )

        val d = DashboardBuilder.build(messages, DashboardQuery(), now, zone, budgets)
        val food = d.categories.first { it.category == MessageCategory.FOOD }
        assertAmount("200", food.remaining!!)
        assertEquals(0.8, food.usedFraction!!, 0.0001)
        assertEquals(0, d.categories.first { it.category == MessageCategory.SHOPPING }.count)
        assertNull(d.categories.first { it.category == MessageCategory.UPI_PAYMENT }.budget)

        val lastMonth = DashboardBuilder.build(messages, DashboardQuery(period = DashboardPeriod.LAST_MONTH), now, zone, budgets)
        assertTrue(lastMonth.categories.all { it.budget == null })
        val received = DashboardBuilder.build(messages, DashboardQuery(direction = FlowDirection.RECEIVED), now, zone, budgets)
        assertTrue(received.categories.all { it.budget == null })
    }
}
