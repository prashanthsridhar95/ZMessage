package com.zyke.message.domain

import com.zyke.message.domain.ai.AiAsker
import com.zyke.message.domain.ai.AiAvailability
import com.zyke.message.domain.ai.AiGateway
import com.zyke.message.domain.ai.AiResult
import com.zyke.message.domain.ai.AskAnswer
import com.zyke.message.domain.ai.AskExecutor
import com.zyke.message.domain.ai.AskGroup
import com.zyke.message.domain.ai.AskMetric
import com.zyke.message.domain.ai.AskOutcome
import com.zyke.message.domain.ai.AskParser
import com.zyke.message.domain.ai.AskPeriod
import com.zyke.message.domain.ai.AskQuery
import com.zyke.message.domain.ai.Guarded
import com.zyke.message.domain.ai.OnDeviceAi
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAskTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 26) // a Thursday
    private var id = 1L
    private val payees = listOf("Swiggy", "Cafe Mocha", "Landlord", "Zomato")

    private fun tx(amount: String, on: LocalDate, who: String, category: MessageCategory, received: Boolean = false, card: Boolean = false) = ClassifiedMessage(
        SmsMessage(id++, "AD-BANK", "x", on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true),
        category, Merchant(who, MerchantLogo.UPI, category),
        MessageContent.Transaction(BankName.HDFC, who, amount, "1234", received, card)
    )

    private val messages = listOf(
        tx("1250", LocalDate.of(2026, 11, 20), "Cafe Mocha", MessageCategory.FOOD),
        tx("640", LocalDate.of(2026, 11, 18), "Swiggy", MessageCategory.FOOD),
        tx("830", LocalDate.of(2026, 11, 3), "Zomato", MessageCategory.FOOD),
        tx("8500", LocalDate.of(2026, 11, 2), "Landlord", MessageCategory.OTHERS),
        tx("52000", LocalDate.of(2026, 11, 1), "Acme", MessageCategory.SALARY, received = true),
        tx("200", LocalDate.of(2026, 11, 19), "Swiggy", MessageCategory.FOOD, received = true, card = true), // a refund
        tx("999", LocalDate.of(2026, 10, 25), "Swiggy", MessageCategory.FOOD)
    )

    private fun query(metric: AskMetric = AskMetric.SPENT, category: MessageCategory? = null, payee: String? = null, period: AskPeriod, group: AskGroup = AskGroup.NONE) =
        AskQuery(metric, category?.let { com.zyke.message.domain.ai.AiCategory(it, null, it.displayString) }, payee, period, group)

    private fun run(q: AskQuery) = AskExecutor.run(q, messages, today, zone)

    // ---- the whitelist ----

    @Test fun aGoodQueryIsUnderstood() {
        val g = AskParser.parse("""{"metric":"spent","category":"Food","payee":null,"period":"last_week","group_by":"none"}""", "how much on food last week", payees)
        val q = (g as Guarded.Accepted).value
        assertEquals(AskMetric.SPENT, q.metric); assertEquals(MessageCategory.FOOD, q.category?.builtIn); assertEquals(AskPeriod.LAST_WEEK, q.period)
    }

    @Test fun anythingOutsideTheVocabularyIsRefused() {
        fun bad(json: String, q: String = "x question") = AskParser.parse(json, q, payees) is Guarded.Rejected
        assertTrue("unknown metric", bad("""{"metric":"delete_all","period":"today"}"""))
        assertTrue("unknown period", bad("""{"metric":"spent","period":"since_the_dawn_of_time"}"""))
        assertTrue("unknown grouping", bad("""{"metric":"spent","period":"today","group_by":"sender_phone"}"""))
        assertTrue("unknown category", bad("""{"metric":"spent","period":"today","category":"Gambling"}"""))
        assertTrue("not json", bad("The answer is 4820 rupees"))
        assertTrue("sql", bad("""{"metric":"spent; DROP TABLE","period":"today"}"""))
    }

    @Test fun aPayeeMustBeOneThePersonHasAndTheQuestionMentioned() {
        fun parse(payee: String, question: String) = AskParser.parse("""{"metric":"spent","payee":"$payee","period":"this_month","group_by":"none"}""", question, payees)
        assertEquals("Swiggy", ((parse("swiggy", "how much did I pay swiggy this month")) as Guarded.Accepted).value.payee)
        assertEquals("Cafe Mocha", ((parse("cafe moca", "spent at cafe moca this month")) as Guarded.Accepted).value.payee)
        assertTrue("a payee the person never paid", parse("Starbucks", "how much at starbucks this month") is Guarded.Rejected)
        assertTrue("a payee the model made up", parse("Swiggy", "how much did I spend this month") is Guarded.Rejected)
    }

    @Test fun periodsAreResolvedByTheCalendar() {
        assertEquals(LocalDate.of(2026, 11, 16)..LocalDate.of(2026, 11, 22), AskPeriod.LAST_WEEK.resolve(today).let { it.start..it.endInclusive })
        assertEquals(LocalDate.of(2026, 11, 23), AskPeriod.THIS_WEEK.resolve(today).start)
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31), AskPeriod.LAST_MONTH.resolve(today).let { it.start..it.endInclusive })
        assertEquals(LocalDate.of(2025, 12, 31), AskPeriod.LAST_YEAR.resolve(today).endInclusive)
        assertEquals(today.minusDays(6), AskPeriod.LAST_7_DAYS.resolve(today).start)
    }

    // ---- the executor uses the app's own counting ----

    @Test fun spentIsCountedLikeTheRestOfTheApp() {
        // 1250 + 640 + 830 food this month, less the 200 refund on the card.
        val r = run(query(category = MessageCategory.FOOD, period = AskPeriod.THIS_MONTH))
        assertEquals(0, BigDecimal("2520").compareTo(r.figure))
        assertEquals(3, r.count)
        assertEquals("newest first", "Cafe Mocha", r.matches.first().merchant.name)
    }

    @Test fun incomeIsNotSpendingAndTheOtherWayRound() {
        assertEquals(0, BigDecimal("52000").compareTo(run(query(metric = AskMetric.INCOME, period = AskPeriod.THIS_MONTH)).figure))
        // 1250 + 640 + 830 + 8500 spent, less the 200 refund on the card; the salary is not spending.
        assertEquals(0, BigDecimal("11020").compareTo(run(query(period = AskPeriod.THIS_MONTH)).figure))
    }

    @Test fun theBiggestPaymentListsBiggestFirst() {
        val r = run(query(metric = AskMetric.BIGGEST, period = AskPeriod.THIS_MONTH))
        assertEquals(0, BigDecimal("8500").compareTo(r.figure))
        assertEquals("Landlord", r.matches.first().merchant.name)
        assertTrue(AskAnswer.of(r, zone).sentence.contains("Landlord"))
    }

    @Test fun aPayeeFilterAndGroupingWork() {
        val r = run(query(payee = "Swiggy", period = AskPeriod.ALL_TIME))
        assertEquals(2, r.count) // 640 and 999 (the card refund is not a payment)
        val g = run(query(period = AskPeriod.THIS_MONTH, group = AskGroup.PAYEE))
        assertEquals("Landlord", g.rows.first().label)
        val c = run(query(period = AskPeriod.THIS_MONTH, group = AskGroup.CATEGORY))
        assertEquals("Others", c.rows.first().label)
        val m = run(query(period = AskPeriod.ALL_TIME, group = AskGroup.MONTH))
        assertEquals(setOf("Nov 2026", "Oct 2026"), m.rows.map { it.label }.toSet())
    }

    @Test fun countAndAverage() {
        assertEquals(0, BigDecimal(4).compareTo(run(query(metric = AskMetric.COUNT, period = AskPeriod.THIS_MONTH)).figure))
        assertEquals(0, BigDecimal("2805").compareTo(run(query(metric = AskMetric.AVERAGE, period = AskPeriod.THIS_MONTH)).figure))
    }

    @Test fun nothingFoundSaysSoAndShowsNoFigure() {
        val t = AskAnswer.of(run(query(category = MessageCategory.TRAVEL, period = AskPeriod.THIS_MONTH)), zone)
        assertEquals(null, t.figure)
        assertTrue(t.sentence.startsWith("No payments"))
    }

    @Test fun theAnswerTextIsBuiltFromComputedFiguresOnly() {
        val r = run(query(category = MessageCategory.FOOD, period = AskPeriod.THIS_MONTH))
        val t = AskAnswer.of(r, zone)
        assertEquals("₹2,520", t.figure)
        assertTrue(t.sentence, t.sentence.startsWith("You spent ₹2,520 on Food this month, across 3 payments"))
    }

    // ---- the model in the loop: a fake engine ----

    private class Engine(private val reply: String?) : OnDeviceAi {
        override val availability: StateFlow<AiAvailability> = MutableStateFlow(AiAvailability.Ready)
        override suspend fun refresh() = Unit
        override suspend fun download() = Unit
        override suspend fun generate(prompt: String, maxTokens: Int): AiResult =
            if (reply == null) AiResult.Failure(com.zyke.message.domain.ai.AiFailure.ERROR) else AiResult.Text(reply, 0)
        override fun close() = Unit
    }

    @Test fun theAskerNeverRunsAnythingItCannotUnderstand() = runBlocking {
        val good = AiAsker(AiGateway(Engine("""{"metric":"biggest","category":null,"payee":null,"period":"this_month","group_by":"none"}""")))
        assertTrue(good.understand("biggest payment this month?", payees, emptyMap(), today) is AskOutcome.Understood)
        val bad = AiAsker(AiGateway(Engine("I would say about 5000")))
        assertTrue(bad.understand("biggest payment this month?", payees, emptyMap(), today) is AskOutcome.Refused)
        val down = AiAsker(AiGateway(Engine(null)))
        assertTrue(down.understand("biggest payment this month?", payees, emptyMap(), today) is AskOutcome.Failed)
        assertTrue(good.understand("hi", payees, emptyMap(), today) is AskOutcome.Refused)
    }
}
