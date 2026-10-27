package com.zyke.message.domain

import com.zyke.message.domain.ai.ModelTransactionParser
import com.zyke.message.domain.ai.OnDeviceModel
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.classification.SpamScorer
import com.zyke.message.domain.feed.QueryInterpreter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.feed.StatusFilter
import com.zyke.message.domain.feed.TimeWindow
import com.zyke.message.domain.insights.AlertKind
import com.zyke.message.domain.insights.CategorySuggester
import com.zyke.message.domain.insights.SpendAlerts
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceInsightsTest {
    private val classifier = MessageClassifier()
    private var nextId = 1L
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun pay(payee: String, amount: String, at: Long) = classifier.classify(
        SmsMessage(nextId++, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C x1234\nTo $payee\nOn 26/10/26\nRef ${nextId}", at, true)
    )

    // ---- category suggestions

    @Test
    fun suggester_learnsFromFiledNames_andOnlyForNamesSharingWords() {
        val filed = listOf(
            pay("Hotel Saravana Bhavan", "100", now) to MessageCategory.FOOD,
            pay("Hotel Annapoorna", "100", now) to MessageCategory.FOOD,
            pay("Murugan Meals Hotel", "100", now) to MessageCategory.FOOD,
            pay("Shell Petrol Point", "100", now) to MessageCategory.FUEL,
            pay("Reliance Petrol Point", "100", now) to MessageCategory.FUEL
        ).map { (message, category) -> message.copy(category = category) }
        val unknown = pay("Hotel Murugan Bhavan", "100", now)
        val unrelated = pay("Ramesh Kumar", "100", now)

        val annotated = CategorySuggester.annotate(filed + unknown + unrelated)
        assertEquals(MessageCategory.FOOD, annotated.first { it.id == unknown.id }.suggestedCategory)
        assertNull(annotated.first { it.id == unrelated.id }.suggestedCategory)
        // It only suggests; the category itself is untouched.
        assertEquals(MessageCategory.OTHERS, annotated.first { it.id == unknown.id }.category)
    }

    // ---- spam

    @Test
    fun spamScorer_flagsPitches_butNotRealMessages() {
        listOf(
            "Rs 9,999 profit in 48 hours. Last entries. https://tinyurl.com/x",
            "Your Personal Loan request has been approved for Rs.9,99,999. View next steps: compact.link/BTPCa",
            "You can work from home. Salary Rs 20000 daily income. api.whatsapp.com/send?phone=919999999999"
        ).forEach { assertTrue(it, SpamScorer.isSpam("+919876543210", it)) }

        listOf(
            "Rs.540.00 debited from A/c XX5765 to BURMY FOODS. Ref 123. If not you call 1800",
            "123456 is your OTP for login. Do not share. Valid for 10 minutes.",
            "Your SIP of Rs.5,000 is processed. Units allotted. Statement available."
        ).forEach { assertFalse(it, SpamScorer.isSpam("AD-HDFCBK", it)) }
    }

    // ---- search phrases

    @Test
    fun queryInterpreter_turnsEverydayPhrasesIntoFilters() {
        val a = QueryInterpreter.interpret("show unread bills this week")
        assertEquals(SmartFilter.BILLS, a.filter.smart)
        assertEquals(StatusFilter.UNREAD, a.filter.status)
        assertEquals(TimeWindow.WEEK, a.filter.window)
        assertEquals("", a.text)

        val b = QueryInterpreter.interpret("fuel last 30 days")
        assertEquals(MessageCategory.FUEL, b.filter.category)
        assertEquals(TimeWindow.MONTH, b.filter.window)

        val plain = QueryInterpreter.interpret("swiggy order")
        assertFalse(plain.hasFilters)
        assertEquals("swiggy order", plain.text)
        assertNull(plain.note)
    }

    // ---- alerts

    @Test
    fun alerts_findDoubleCharges_andUnusuallyLargePayments() {
        val history = listOf(
            pay("Cafe Mocha", "600", now - 40 * day), pay("Cafe Mocha", "650", now - 30 * day), pay("Cafe Mocha", "700", now - 20 * day)
        )
        val big = pay("Cafe Mocha", "4000", now - 1 * day)
        val first = pay("Netflix", "649", now - 2 * day)
        val second = pay("Netflix", "649", now - 2 * day + 5 * minute)

        val alerts = SpendAlerts.find((history + big + first + second).sortedByDescending { it.message.date }, now)
        assertTrue(alerts.any { it.kind == AlertKind.UNUSUALLY_HIGH && it.message.id == big.id })
        assertTrue(alerts.any { it.kind == AlertKind.CHARGED_TWICE && it.message.id == second.id })
        assertFalse(alerts.any { it.message.id == first.id })
    }

    // ---- on-device model

    private class FakeModel(private val reply: String?, override val isAvailable: Boolean = true) : OnDeviceModel {
        override suspend fun generate(prompt: String): String? = reply
    }

    @Test
    fun modelAnswers_areCheckedAgainstTheMessage() = runBlocking {
        val body = "Txn of Rs 1,250.00 at Cafe Mocha done on your card. Thanks"

        val good = ModelTransactionParser(FakeModel("""{"amount":"1250.00","direction":"debit","payee":"Cafe Mocha"}""")).parse(body)
        assertNotNull(good)
        assertEquals("Cafe Mocha", good!!.payee)
        assertEquals(BankTransactionType.SENT, good.type)

        // An amount that is not in the text is rejected, and so is an invented payee.
        assertNull(ModelTransactionParser(FakeModel("""{"amount":"9999","direction":"debit","payee":"Cafe Mocha"}""")).parse(body))
        assertEquals("", ModelTransactionParser(FakeModel("""{"amount":"1250","direction":"debit","payee":"Someone Else"}""")).parse(body)!!.payee)
        assertNull(ModelTransactionParser(FakeModel("""{"amount":"1250","direction":"none","payee":""}""")).parse(body))
        assertNull(ModelTransactionParser(FakeModel("not json")).parse(body))
        assertNull(ModelTransactionParser(FakeModel("""{"amount":"1250","direction":"debit","payee":""}""", isAvailable = false)).parse(body))
    }
}
