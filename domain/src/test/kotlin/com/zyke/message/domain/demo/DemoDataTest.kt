package com.zyke.message.domain.demo

import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.ledger.CardBillPayments
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.SalaryDetector
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val nowAt = ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, zone)
    private val now = nowAt.toInstant().toEpochMilli()
    private val today: LocalDate = nowAt.toLocalDate()
    private val data = DemoData.build(now, zone)
    private val classifier = MessageClassifier()

    private class Inbox(private val list: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(list)
    }

    private class Overrides(map: Map<String, MessageCategory>) : CategoryOverrideRepository {
        override val overrides: StateFlow<Map<String, MessageCategory>> = MutableStateFlow(map)
        override fun set(key: String, category: MessageCategory?) = Unit
        override fun clearAll() = Unit
    }

    private class Names(map: Map<String, String>) : NicknameRepository {
        override val nicknames: StateFlow<Map<String, String>> = MutableStateFlow(map)
        override fun set(key: String, name: String?) = Unit
    }

    private fun piped(): List<ClassifiedMessage> = runBlocking {
        ObserveMessagesUseCase(
            Inbox(data.messages), classifier, Dispatchers.Unconfined,
            categoryOverrides = Overrides(data.categories), nicknameRepository = Names(data.nicknames),
            cardRepository = object : com.zyke.message.domain.repository.CardRepository {
                override val cards = MutableStateFlow(data.cards)
                override fun save(card: com.zyke.message.domain.model.UserCard) = Unit
                override fun remove(id: String) = Unit
                override fun clearAll() = Unit
            }
        )().first()
    }

    private val messages by lazy { piped() }

    @Test
    fun deterministic_forTheSameInstant_andFollowsNowWhenItMoves() {
        val again = DemoData.build(now, zone)
        assertEquals(data.messages, again.messages)
        assertEquals(data.cards, again.cards)
        assertEquals(data.goals, again.goals)
        val later = DemoData.build(now + 40L * 86_400_000L, zone)
        assertNotEquals(data.messages.last().date, later.messages.last().date)
        // Always current: the newest message is from today, and nothing is dated in the future.
        assertEquals(today, LocalDate.ofInstant(Instant.ofEpochMilli(data.messages.last().date), zone))
        assertTrue(data.messages.all { it.date <= now })
        val laterToday = LocalDate.ofInstant(Instant.ofEpochMilli(later.messages.last().date), zone)
        assertEquals(LocalDate.ofInstant(Instant.ofEpochMilli(now + 40L * 86_400_000L), zone), laterToday)
    }

    @Test
    fun hasSixMonthsOfHistory_withIdsGrowingWithTheDate() {
        val first = LocalDate.ofInstant(Instant.ofEpochMilli(data.messages.first().date), zone)
        assertTrue("first message $first", first.isBefore(today.minusMonths(5)))
        assertEquals(data.messages.map { it.id }, data.messages.map { it.id }.sorted())
        assertEquals(data.messages.size, data.messages.map { it.id }.distinct().size)
        assertTrue(data.messages.size in 250..600)
        assertTrue(data.messages.any { !it.isRead })
        assertTrue(data.messages.count { !it.isRead } < 10)
    }

    @Test
    fun everyMessageIsReadByTheRealClassifier_andTheCardsAreExercised() {
        val classified = data.messages.map { classifier.classify(it) }
        assertTrue(classified.count { it.content is MessageContent.Transaction } > 200)
        assertTrue(classified.count { it.content is MessageContent.CardBill } >= 4)
        assertTrue(classified.count { it.content is MessageContent.Otp } >= 3)
        assertTrue(classified.any { it.content is MessageContent.FastTag })
        assertTrue(classified.any { it.content is MessageContent.ScheduledPayment })
        // Money messages are never left as plain text.
        val plain = classified.filter { it.content == MessageContent.Generic }
        plain.forEach { assertFalse("read as plain text: ${it.message.body}", it.message.body.contains("Rs", ignoreCase = true) && !it.isPromotional) }
        // At least a few are not money at all (a parcel, data use, a promotion).
        assertTrue(classified.any { it.isPromotional })
    }

    @Test
    fun accountsAndTheirBalances_areKnown() {
        val overview = Accounts.build(messages, data.cards, Instant.ofEpochMilli(now), zone)
        val lasts = overview.accounts.map { it.last4 }.toSet()
        assertTrue(lasts.containsAll(setOf(DemoData.MAIN_ACCOUNT, DemoData.SECOND_ACCOUNT, DemoData.CREDIT_CARD)))
        val balances = BalanceReader.latest(messages, zone).associateBy { it.account }
        assertEquals(BigDecimal("118640.00").compareTo(balances.getValue(DemoData.MAIN_ACCOUNT).amount), 0)
        assertEquals(BigDecimal("31420.00").compareTo(balances.getValue(DemoData.SECOND_ACCOUNT).amount), 0)
        assertTrue(overview.totalBalance!!.signum() > 0)
    }

    @Test
    fun salaryIsFound_andTheMonthSummaryHasIncomeAndSpending() {
        val salary = SalaryDetector.detect(messages, Instant.ofEpochMilli(now), zone)
        assertTrue("salary found", salary != null)
        assertEquals(1, salary!!.typicalDay)
        assertTrue(salary.typical >= BigDecimal(80_000))
        val summary = MonthlySummary.build(messages, Instant.ofEpochMilli(now), zone)
        assertTrue(summary.income.signum() > 0)
        assertTrue(summary.spent.signum() > 0)
        assertTrue(summary.previousSpent.signum() > 0)
    }

    @Test
    fun cardBillPayments_areNotCountedAsSpendingAgain() {
        val paid = messages.filter { CardBillPayments.isCountedAsBillPayment(it) }
        assertTrue("card bill payments found: ${paid.size}", paid.size >= 3)
        paid.forEach { assertTrue(Counting.of(it) !is com.zyke.message.domain.ledger.Counted.Spend) }
        // The month the last payment fell in lists it as a bill payment, outside the spending total.
        val summary = MonthlySummary.build(messages, Instant.ofEpochMilli(now), zone, com.zyke.message.domain.cycle.CycleRule.CALENDAR)
        val counted = messages.sumOf { Counting.spend(it) ?: BigDecimal.ZERO }
        val everyDebit = messages.sumOf { m ->
            val tx = m.content as? MessageContent.Transaction
            if (tx != null && !tx.isReceived) tx.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO else BigDecimal.ZERO
        }
        assertTrue("debits $everyDebit vs counted $counted", everyDebit > counted)
        assertTrue(summary.spent < everyDebit)
    }

    @Test
    fun theCardBillIsDueSoon_andEarlierOnesArePaid() {
        val bills = CardBillsBuilder.build(messages, today)
        val card = bills.single { it.accountNumber == DemoData.CREDIT_CARD }
        val due = card.latest.dueDate!!
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
        assertTrue("due in $days days", days in 1..10)
        assertEquals(BigDecimal("18430.00").compareTo(card.latest.amount), 0)
        assertTrue(card.earlier.size >= 2)
    }

    @Test
    fun subscriptionsAndRecurringPayments_areFound_includingTheSipSeries() {
        val found = Subscriptions.find(messages, Instant.ofEpochMilli(now), zone)
        val names = found.map { it.payee.lowercase() }
        assertTrue("subscriptions: $names", names.any { "netflix" in it })
        assertTrue(names.any { "spotify" in it })
        val sips = found.filter { "index fund sip" in it.payee.lowercase() || "iccl" in it.payee.lowercase() }
        assertEquals("four SIP mandates: $names", 4, sips.size)
        assertEquals(4, sips.map { it.key }.distinct().size)
    }

    @Test
    fun categoriesAreFilledIn_forTheWholeSpread() {
        val spent = messages.filter { Counting.spend(it) != null }.groupBy { it.category }
        for (category in listOf(
            MessageCategory.FOOD, MessageCategory.GROCERIES, MessageCategory.SHOPPING, MessageCategory.TRAVEL, MessageCategory.FUEL,
            MessageCategory.ENTERTAINMENT, MessageCategory.UTILITIES, MessageCategory.INVESTMENTS, MessageCategory.FAMILY
        )) assertTrue("nothing in $category", spent[category].orEmpty().isNotEmpty())
        // Every choice in the table is used by some payee (no stray key that would never match).
        val keys = messages.mapNotNull { com.zyke.message.domain.categories.CategoryKey.of(it) }.toSet()
        data.categories.keys.forEach { assertTrue("unused category key $it", it in keys || data.nicknames.containsKey(it)) }
    }

    @Test
    fun theSetupIsComplete() {
        assertTrue(data.cards.size >= 3 && data.goals.size >= 2 && data.holdings.size >= 3 && data.loans.isNotEmpty())
        assertTrue(data.plan.salary != null && data.plan.items.isNotEmpty())
        assertTrue(data.budgets.isNotEmpty() && data.dailyBudgets.isNotEmpty())
        assertTrue(data.netWorth.isNotEmpty() && data.reminders.isNotEmpty() && data.events.isNotEmpty())
        assertTrue(data.watch.enabled && data.watch.symbols.isNotEmpty())
        // Every watched ticker (and the dollar and gold) has a fixed price, so the strip never needs the network.
        val needed = com.zyke.message.domain.markets.MarketSymbols.needed(data.watch)
        assertTrue(data.quotes.keys.containsAll(needed))
        val rows = com.zyke.message.domain.markets.MarketSymbols.rows(data.watch, data.quotes)
        assertTrue(rows.size >= 5)
        assertEquals(data.goals.map { it.id }.distinct().size, data.goals.size)
    }

    @Test
    fun theFictionalPerson_hasNoRealLookingPersonalData() {
        val joined = data.messages.joinToString("\n") { it.body + " " + it.sender }
        // No ordinary mobile number (10 digits starting 6 to 9) and no e-mail address.
        assertFalse(Regex("(?<!\\d)[6-9]\\d{9}(?!\\d)").containsMatchIn(joined))
        assertFalse(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").containsMatchIn(joined))
        // Account endings are the made-up ones, and names are the invented ones.
        val endings = Regex("(?:A/c|A/C|a/c|Card|Tag)\\s*(?:no\\.?\\s*)?[xX*]*(\\d{4})\\b").findAll(joined).map { it.groupValues[1] }.toSet()
        assertTrue("endings $endings", endings.all { it in setOf("4821", "2096", "5580", "7788") })
        assertTrue(joined.contains("Meena Menon") && joined.contains("Sunita Rao") && joined.contains("Rohan Iyer"))
        assertEquals("Asha Menon", DemoData.OWNER)
        assertTrue(data.goals.none { it.name.contains("real", ignoreCase = true) })
    }

    @Test
    fun theChartForTheStockSheet_isMadeUpAndEndsOnThePrice() {
        for (range in com.zyke.message.domain.markets.ChartRange.entries) {
            val history = DemoData.priceHistory("TCS.NS", range, 3318.0, 3296.45, now)
            assertTrue(history.points.size >= 20)
            assertEquals(3318.0, history.points.last().value, 0.0001)
            assertTrue(history.points.zipWithNext().all { (a, b) -> a.time < b.time })
        }
        assertEquals(DemoData.priceHistory("TCS.NS", com.zyke.message.domain.markets.ChartRange.MONTH, 3318.0, null, now),
            DemoData.priceHistory("TCS.NS", com.zyke.message.domain.markets.ChartRange.MONTH, 3318.0, null, now))
    }
}
