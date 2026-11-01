package com.zyke.message.domain

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.insights.Cadence
import com.zyke.message.domain.insights.CancelGuides
import com.zyke.message.domain.insights.CancelledCharges
import com.zyke.message.domain.insights.HeadsUpKind
import com.zyke.message.domain.insights.HeadsUps
import com.zyke.message.domain.insights.HintKind
import com.zyke.message.domain.insights.PaymentChannel
import com.zyke.message.domain.insights.PaymentChannels
import com.zyke.message.domain.insights.RecurringPayments
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.insights.SubscriptionOverview
import com.zyke.message.domain.insights.SubscriptionSort
import com.zyke.message.domain.insights.SubscriptionState
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.plan.RunwayStatus
import com.zyke.message.domain.plan.SalaryRunways
import com.zyke.message.domain.repository.RecurringState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionsPageTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 25)
    private val now = today.atTime(9, 0).atZone(zone).toInstant()
    private var nextId = 1L

    private fun tx(
        payee: String, amount: String, on: LocalDate, body: String = "Rs $amount debited to $payee", isCard: Boolean = false,
        aside: SetAside? = null, received: Boolean = false
    ) = ClassifiedMessage(
        message = SmsMessage(nextId++, "AD-HDFCBK", body, on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true),
        category = MessageCategory.ENTERTAINMENT,
        merchant = Merchant(payee, MerchantLogo.UPI, MessageCategory.ENTERTAINMENT),
        content = MessageContent.Transaction(BankName.HDFC, payee, amount, "5678", received, isCard),
        setAside = aside
    )

    /** Three monthly charges ending [lastAgo] days ago. */
    private fun monthly(payee: String, amounts: List<String>, lastAgo: Long = 5, body: String? = null, isCard: Boolean = false) =
        amounts.mapIndexed { i, a ->
            val on = today.minusDays(lastAgo).minusDays(30L * (amounts.size - 1 - i))
            if (body == null) tx(payee, a, on, isCard = isCard) else tx(payee, a, on, body, isCard)
        }

    private fun overview(
        messages: List<ClassifiedMessage>, states: Map<String, RecurringState> = emptyMap(), cancelled: Map<String, LocalDate> = emptyMap()
    ): SubscriptionOverview {
        val subs = Subscriptions.find(messages, now, zone)
        return SubscriptionOverview.build(subs, states, cancelled, today, messages, zone)
    }

    // ---- cadence and totals ----

    @Test
    fun cadenceConvertsToAYearAndAMonth() {
        val amount = BigDecimal("100")
        assertEquals(BigDecimal("5200"), Cadence.WEEKLY.yearly(amount))
        assertEquals(BigDecimal("1200"), Cadence.MONTHLY.yearly(amount))
        assertEquals(BigDecimal("400"), Cadence.QUARTERLY.yearly(amount))
        assertEquals(BigDecimal("100"), Cadence.YEARLY.yearly(amount))
        assertEquals(0, BigDecimal("100.00").compareTo(Cadence.MONTHLY.monthly(amount)))
        assertEquals(0, BigDecimal("33.33").compareTo(Cadence.QUARTERLY.monthly(amount)))
        assertEquals(0, BigDecimal("8.33").compareTo(Cadence.YEARLY.monthly(amount)))
        assertEquals(0, BigDecimal("433.33").compareTo(Cadence.WEEKLY.monthly(amount)))
    }

    @Test
    fun gapsMapToACadence() {
        assertEquals(Cadence.WEEKLY, Cadence.fromGapDays(7))
        assertEquals(Cadence.MONTHLY, Cadence.fromGapDays(30))
        assertEquals(Cadence.QUARTERLY, Cadence.fromGapDays(91))
        assertEquals(Cadence.YEARLY, Cadence.fromGapDays(365))
        assertNull(Cadence.fromGapDays(15))
    }

    @Test
    fun totalsCountOnlyActiveSubscriptions_sortedByYearlyCost() {
        val messages = monthly("Netflix", listOf("649", "649", "649")) + monthly("Spotify", listOf("119", "119", "119")) +
            monthly("Gym", listOf("2000", "2000", "2000"))
        val result = overview(messages)
        assertEquals(listOf("Gym", "Netflix", "Spotify"), result.lines.map { it.name })
        assertEquals(3, result.activeCount)
        assertEquals(BigDecimal((2000 + 649 + 119) * 12), result.yearlyTotal)
        assertEquals(BigDecimal(2000 + 649 + 119), result.monthlyTotal)
    }

    // ---- state ----

    @Test
    fun cancelledOnesAreListedLastAndLeftOutOfTheTotals() {
        val messages = monthly("Netflix", listOf("649", "649", "649")) + monthly("Gym", listOf("2000", "2000", "2000"))
        val result = overview(messages, cancelled = mapOf(Subscriptions.keyFor("Gym") to today.minusDays(1)))
        assertEquals(listOf("Netflix", "Gym"), result.lines.map { it.name })
        assertEquals(SubscriptionState.CANCELLED, result.lines.last().state)
        assertEquals(1, result.activeCount)
        assertEquals(BigDecimal(649 * 12), result.yearlyTotal)
    }

    @Test
    fun aSubscriptionNotSeenForLongerThanItsGapIsPaused_andNotCounted() {
        // Last charge 45 days ago: still recognised, but a monthly charge is late.
        val result = overview(monthly("Netflix", listOf("649", "649", "649"), lastAgo = 45))
        assertEquals(SubscriptionState.PAUSED, result.lines.single().state)
        assertEquals(0, result.activeCount)
        assertEquals(BigDecimal.ZERO, result.yearlyTotal)
    }

    @Test
    fun ignoredAreNotShown() {
        val messages = monthly("Netflix", listOf("649", "649", "649"))
        val key = Subscriptions.keyFor("Netflix")
        assertTrue(overview(messages, states = mapOf(key to RecurringState.IGNORED)).isEmpty)
        assertFalse(overview(messages, states = mapOf(key to RecurringState.CONFIRMED)).isEmpty)
    }

    @Test
    fun paymentsSetAsideAreNeverSubscriptions() {
        val aside = SetAside.EXCLUDED
        val messages = monthly("Netflix", listOf("649", "649", "649")).map { it.copy(setAside = aside) }
        assertTrue(overview(messages).isEmpty)
    }

    @Test
    fun cancelledOnesLeaveTheUpcomingList() {
        val messages = monthly("Netflix", listOf("649", "649", "649"))
        val subs = Subscriptions.find(messages, now, zone)
        assertEquals(1, RecurringPayments.arrange(subs, emptyMap()).size)
        assertTrue(RecurringPayments.arrange(subs, emptyMap(), setOf(Subscriptions.keyFor("Netflix"))).isEmpty())
    }

    // ---- price change ----

    @Test
    fun aRiseOfMoreThanThreePercentIsFlagged() {
        val rose = overview(monthly("Netflix", listOf("499", "499", "599"))).lines.single()
        assertEquals(BigDecimal("499"), rose.priceChange?.from)
        assertEquals(BigDecimal("599"), rose.priceChange?.to)
        assertEquals(20, rose.priceChange?.percent)
        assertTrue(rose.hints.any { it.kind == HintKind.PRICE_UP })
        // 3% exactly and a fall are not flagged.
        assertNull(overview(monthly("A", listOf("100", "100", "103"))).lines.single().priceChange)
        assertNull(overview(monthly("B", listOf("649", "649", "499"))).lines.single().priceChange)
        assertNotNull(overview(monthly("C", listOf("100", "100", "104"))).lines.single().priceChange)
    }

    // ---- hints ----

    @Test
    fun twoVideoServicesPointAtEachOther_asAQuestion() {
        val messages = monthly("Netflix", listOf("649", "649", "649")) + monthly("Hotstar", listOf("299", "299", "299")) +
            monthly("Spotify", listOf("119", "119", "119"))
        val result = overview(messages)
        val netflix = result.lines.first { it.name == "Netflix" }
        val similar = netflix.hints.single { it.kind == HintKind.SIMILAR }
        assertTrue(similar.text.contains("Hotstar") && similar.text.endsWith("?"))
        assertTrue(result.lines.first { it.name == "Spotify" }.hints.none { it.kind == HintKind.SIMILAR })
    }

    @Test
    fun aCancelledServiceDoesNotCountAsASimilarOne() {
        val messages = monthly("Netflix", listOf("649", "649", "649")) + monthly("Hotstar", listOf("299", "299", "299"))
        val result = overview(messages, cancelled = mapOf(Subscriptions.keyFor("Hotstar") to today))
        assertTrue(result.lines.first { it.name == "Netflix" }.hints.isEmpty())
    }

    @Test
    fun aNewSubscriptionIsAskedAboutOnlyWhenTheInboxReachesFurtherBack() {
        val fresh = monthly("Gym", listOf("999", "999", "999"), lastAgo = 5) // first charge 65 days ago: not new enough
        assertTrue(overview(fresh).lines.single().hints.none { it.kind == HintKind.NEW })
        val young = (0..2).map { tx("Gym", "999", today.minusDays(5 + 27L * (2 - it))) } // first 59 days ago
        val old = tx("Rent", "100", today.minusDays(200))
        assertTrue("no older history, no claim", overview(young).lines.single().hints.none { it.kind == HintKind.NEW })
        val hint = overview(young + old).lines.single { it.name == "Gym" }.hints.single { it.kind == HintKind.NEW }
        assertTrue(hint.text.endsWith("?"))
    }

    // ---- channel and guide ----

    @Test
    fun channelIsReadFromTheWords() {
        assertEquals(PaymentChannel.UPI_AUTOPAY, PaymentChannels.infer(listOf("Rs 649 debited via UPI AutoPay mandate"), false))
        assertEquals(PaymentChannel.CARD_EMANDATE, PaymentChannels.infer(listOf("Rs 649 spent on card"), true))
        assertEquals(PaymentChannel.STANDING_INSTRUCTION, PaymentChannels.infer(listOf("NACH debit Rs 999 for GYM"), false))
        assertEquals(PaymentChannel.APP_STORE, PaymentChannels.infer(listOf("Google Play: Rs 129 paid"), false))
        assertEquals(PaymentChannel.DIRECT, PaymentChannels.infer(listOf("Rs 999 sent via UPI to GYM"), false))
    }

    @Test
    fun theOverviewCarriesChannelAndLastFourDigits() {
        val line = overview(monthly("Netflix", listOf("649", "649", "649"), body = "Rs 649 debited via UPI AutoPay to Netflix")).lines.single()
        assertEquals(PaymentChannel.UPI_AUTOPAY, line.channel)
        assertEquals("5678", line.account)
    }

    @Test
    fun theGuideNamesTheMerchant_andOnlyPlayOffersTheLink() {
        PaymentChannel.values().forEach { channel ->
            val guide = CancelGuides.guide(channel, "Netflix")
            assertTrue(guide.steps.isNotEmpty())
            assertEquals(channel == PaymentChannel.APP_STORE, guide.offersPlayLink)
        }
        val upi = CancelGuides.guide(PaymentChannel.UPI_AUTOPAY, "Netflix").steps
        assertTrue(upi.any { "Netflix" in it } && upi.any { "Revoke" in it })
        assertTrue(CancelGuides.PLAY_SUBSCRIPTIONS_URL.startsWith("https://play.google.com/"))
    }

    @Test
    fun theReferenceHasNoMessageText() {
        val line = overview(monthly("Netflix", listOf("649", "649", "649"), body = "SECRET BODY Rs 649 UPI AutoPay Netflix")).lines.single()
        val text = CancelGuides.reference(line)
        assertTrue("Netflix" in text && "₹649" in text && "5678" in text)
        assertFalse("SECRET" in text)
    }

    // ---- charge after cancelling ----

    private val netflixKey = Subscriptions.keyFor("Netflix")

    @Test
    fun aChargeAfterCancellingIsFound_butNotOneBeforeOrOfAnotherSize() {
        val history = monthly("Netflix", listOf("649", "649", "649"), lastAgo = 20)
        val cancelledOn = today.minusDays(10)
        val after = tx("Netflix", "649", today.minusDays(2))
        val sameDay = tx("Netflix", "649", cancelledOn)
        val other = tx("Netflix", "99", today.minusDays(1))
        val other2 = tx("Hotstar", "649", today.minusDays(1))
        val found = CancelledCharges.find(history + after + sameDay + other + other2, mapOf(netflixKey to cancelledOn), zone, now)
        assertEquals(listOf(after.id), found.map { it.message.id })
        assertEquals(BigDecimal("649"), found.single().amount)
    }

    @Test
    fun theReviewQueueRaisesIt_untilDismissed() {
        val history = monthly("Netflix", listOf("649", "649", "649"), lastAgo = 20)
        val after = tx("Netflix", "649", today.minusDays(2))
        val messages = history + after
        val cancelled = mapOf(netflixKey to today.minusDays(10))
        val queue = ReviewQueues.build(messages, SetAsides(), zone, now, cancelled = cancelled)
        assertEquals(1, queue.cancelledCharges.size)
        assertTrue(queue.count >= 1)
        val dismissed = ReviewQueues.build(messages, SetAsides(dismissed = setOf(queue.cancelledCharges.single().reviewKey)), zone, now, cancelled = cancelled)
        assertTrue(dismissed.cancelledCharges.isEmpty())
        assertTrue(ReviewQueues.build(messages, SetAsides(), zone, now).cancelledCharges.isEmpty())
    }

    @Test
    fun aHeadsUpSaysSo() {
        val history = monthly("Netflix", listOf("649", "649", "649"), lastAgo = 20)
        val after = tx("Netflix", "649", today.minusDays(1))
        val found = HeadsUps.evaluate(after, history, null, zone, after.message.date, mapOf(netflixKey to today.minusDays(10)))
        val note = found.single { it.kind == HeadsUpKind.CANCELLED_CHARGE }
        assertEquals("Netflix charged ₹649 after you marked it cancelled.", note.text)
        assertTrue(HeadsUps.evaluate(after, history, null, zone, after.message.date).none { it.kind == HeadsUpKind.CANCELLED_CHARGE })
    }

    // ---- salary runway ----

    private fun insights(spendable: Int, spent: Int, salaryDay: Int? = 1) = PlanInsights(
        income = BigDecimal(spendable), outflows = BigDecimal.ZERO, investments = BigDecimal.ZERO, savingsGoal = BigDecimal.ZERO,
        spendable = BigDecimal(spendable), spentOnTheRest = BigDecimal(spent), spendableLeft = BigDecimal(spendable - spent),
        daysLeft = 0, perDayLeft = null, salaryReceived = null, salaryStatus = null, items = emptyList(), savedSoFar = BigDecimal.ZERO,
        incomeSource = null, salaryDay = salaryDay, salaryRange = null
    )

    @Test
    fun runwayOnTrackWhenTheMoneyLastsToTheEnd() {
        // Day 10 of October (cycle 1-31): 100 a day, 30000 spendable, 1000 spent.
        val runway = SalaryRunways.project(insights(30000, 1000), LocalDate.of(2026, 10, 10), CycleRule.CALENDAR)!!
        assertEquals(RunwayStatus.ON_TRACK, runway.status)
        assertNull(runway.runsOutOn)
        assertEquals(LocalDate.of(2026, 11, 1), runway.payday)
        assertTrue(runway.paydayIsSalaryDay)
    }

    @Test
    fun runwayRunsOutEarlyAtThePace() {
        // Day 10: 10000 spent in 10 days = 1000 a day; 5000 left covers 5 more days: lasts until the 15th.
        val runway = SalaryRunways.project(insights(15000, 10000), LocalDate.of(2026, 10, 10), CycleRule.CALENDAR)!!
        assertEquals(RunwayStatus.RUNS_OUT_EARLY, runway.status)
        assertEquals(LocalDate.of(2026, 10, 15), runway.runsOutOn)
        assertEquals(17, runway.daysBefore) // to 1 Nov
        val caption = SalaryRunways.caption(runway) { "the ${it.dayOfMonth}th" }
        assertEquals("At this pace your plan's money lasts until the 15th — 17 days before salary", caption)
        assertEquals(32, runway.days.size) // October plus the payday
    }

    @Test
    fun runwayUsedUpWhenNothingIsLeft() {
        val runway = SalaryRunways.project(insights(10000, 12000), LocalDate.of(2026, 10, 10), CycleRule.CALENDAR)!!
        assertEquals(RunwayStatus.USED_UP, runway.status)
        assertEquals(LocalDate.of(2026, 10, 10), runway.runsOutOn)
    }

    @Test
    fun runwayHidesWithoutEnoughData() {
        val cycle = CycleRule.CALENDAR
        assertNull(SalaryRunways.project(null, LocalDate.of(2026, 10, 10), cycle))
        assertNull("plan that does not add up", SalaryRunways.project(insights(-5, 0), LocalDate.of(2026, 10, 10), cycle))
        assertNull("cycle just began", SalaryRunways.project(insights(30000, 500), LocalDate.of(2026, 10, 2), cycle))
        assertNotNull(SalaryRunways.project(insights(30000, 500), LocalDate.of(2026, 10, 3), cycle))
    }

    @Test
    fun noSpendingMeansItLasts() {
        val runway = SalaryRunways.project(insights(30000, 0), LocalDate.of(2026, 10, 10), CycleRule.CALENDAR)!!
        assertEquals(RunwayStatus.ON_TRACK, runway.status)
    }

    @Test
    fun theCaptionOnlyCallsItSalaryWhenTheCycleStartsOnSalaryDay() {
        val other = SalaryRunways.project(insights(15000, 10000, salaryDay = 28), LocalDate.of(2026, 10, 10), CycleRule.CALENDAR)!!
        assertFalse(other.paydayIsSalaryDay)
        assertTrue(SalaryRunways.caption(other) { "the ${it.dayOfMonth}th" }.endsWith("before the end of the cycle"))
    }

    // ---- filter, sort and cost shares ----

    @Test
    fun viewFiltersByStateAndSorts() {
        val messages = monthly("Netflix", listOf("649", "649", "649"), lastAgo = 5) + monthly("Spotify", listOf("119", "119", "119"), lastAgo = 20) +
            monthly("Gym", listOf("2000", "2000", "2000"), lastAgo = 10) + monthly("Hotstar", listOf("299", "299", "299"), lastAgo = 5)
        val cancelledKey = Subscriptions.keyFor("Hotstar")
        val result = overview(messages, cancelled = mapOf(cancelledKey to today.minusDays(1)))
        assertEquals(1, result.count(SubscriptionState.CANCELLED))
        assertEquals(listOf("Gym", "Netflix", "Spotify"), result.view(SubscriptionState.ACTIVE, SubscriptionSort.YEARLY_COST).map { it.name })
        assertEquals(listOf("Gym", "Netflix", "Spotify"), result.view(SubscriptionState.ACTIVE, SubscriptionSort.NAME).map { it.name })
        // Spotify was charged longest ago, so it is due first; Netflix last.
        assertEquals(listOf("Spotify", "Gym", "Netflix"), result.view(SubscriptionState.ACTIVE, SubscriptionSort.NEXT_CHARGE).map { it.name })
        assertEquals(listOf("Hotstar"), result.view(SubscriptionState.CANCELLED, SubscriptionSort.NAME).map { it.name })
        // "All" keeps the cancelled one after the active ones when sorted by next charge.
        assertEquals("Hotstar", result.view(null, SubscriptionSort.NEXT_CHARGE).last().name)
    }

    @Test
    fun costSharesNameTheTopFiveAndLumpTheRestIntoOthers() {
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf")
        val messages = names.withIndex().flatMap { (i, n) -> monthly(n, List(3) { "${1000 - i * 100}" }) }
        val shares = overview(messages).costShares(5)
        assertEquals(6, shares.size)
        assertNull(shares.last().line)
        assertEquals("Alpha", shares.first().line!!.name)
        assertEquals(100, shares.sumOf { it.percent })
        assertTrue(overview(emptyList()).costShares().isEmpty())
    }
}
