package com.zyke.message.domain

import com.zyke.message.domain.ai.AiAvailability
import com.zyke.message.domain.ai.AiBatch
import com.zyke.message.domain.ai.AiCache
import com.zyke.message.domain.ai.AiCategoryIdeas
import com.zyke.message.domain.ai.AiContext
import com.zyke.message.domain.ai.AiFailure
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiGate
import com.zyke.message.domain.ai.AiGateway
import com.zyke.message.domain.ai.AiKeys
import com.zyke.message.domain.ai.AiMonthWriter
import com.zyke.message.domain.ai.AiResult
import com.zyke.message.domain.ai.AiSettings
import com.zyke.message.domain.ai.AiSummarizer
import com.zyke.message.domain.ai.AiSummaryOverlay
import com.zyke.message.domain.ai.AiUnderstander
import com.zyke.message.domain.ai.BatchItem
import com.zyke.message.domain.ai.InMemoryAiCache
import com.zyke.message.domain.ai.MonthFacts
import com.zyke.message.domain.ai.NoOnDeviceAi
import com.zyke.message.domain.ai.OnDeviceAi
import com.zyke.message.domain.ai.SummaryCandidate
import com.zyke.message.domain.ai.Understanding
import com.zyke.message.domain.triage.TriageCard
import com.zyke.message.domain.triage.TriageDeck
import com.zyke.message.domain.triage.TriageStack
import com.zyke.message.domain.triage.TriageMode
import com.zyke.message.domain.triage.TriageTone
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiServicesTest {
    /** A scripted engine: each call takes the next reply (a failure kind or text). */
    private class Script(vararg replies: Any, state: AiAvailability = AiAvailability.Ready) : OnDeviceAi {
        private val queue = ArrayDeque(replies.toList())
        var calls = 0
        var prompts = mutableListOf<String>()
        var slowMs = 0L
        override val availability: StateFlow<AiAvailability> = MutableStateFlow(state)
        override suspend fun refresh() = Unit
        override suspend fun download() = Unit
        override suspend fun generate(prompt: String, maxTokens: Int): AiResult {
            calls++; prompts += prompt
            if (slowMs > 0) delay(slowMs)
            return when (val next = queue.removeFirstOrNull() ?: AiFailure.ERROR) {
                is AiFailure -> AiResult.Failure(next)
                else -> AiResult.Text(next.toString(), 5)
            }
        }
        override fun close() = Unit
    }

    private val body = "Rs. 1,250.00 spent at CAFE MOCHA using card ending 4321. Thanks"
    private val good = """{"kind":"spend","amount":"1250.00","merchant":"CAFE MOCHA","last4":"4321","category":"Food"}"""

    private fun gateway(engine: OnDeviceAi, waits: MutableList<Long> = mutableListOf()) = AiGateway(engine, pause = { waits += it })

    // ---- gateway ----

    @Test fun aBusyPhoneIsRetriedAfterAWaitThenGivenUp() = runBlocking {
        val waits = mutableListOf<Long>()
        val engine = Script(AiFailure.BUSY, AiFailure.QUOTA, "ok")
        val r = gateway(engine, waits).run("p")
        assertEquals("ok", (r as AiResult.Text).text)
        assertEquals("a longer wait each time", listOf(1_500L, 3_000L), waits)
        val never = Script(AiFailure.BUSY, AiFailure.BUSY, AiFailure.BUSY, "late")
        assertEquals(AiFailure.BUSY, (gateway(never).run("p") as AiResult.Failure).kind)
        assertEquals("one try and two retries", 3, never.calls)
    }

    @Test fun aFailureThatWillNotGetBetterIsNotRetried() = runBlocking {
        val e = Script(AiFailure.ERROR, "x")
        assertEquals(AiFailure.ERROR, (gateway(e).run("p") as AiResult.Failure).kind)
        assertEquals(1, e.calls)
    }

    @Test fun aSlowAnswerTimesOut() = runBlocking {
        val e = Script("late").also { it.slowMs = 500 }
        val r = AiGateway(e).run("p", timeoutMs = 50)
        assertEquals(AiFailure.TIMEOUT, (r as AiResult.Failure).kind)
    }

    @Test fun anEmptyAnswerIsAFailure() = runBlocking {
        assertEquals(AiFailure.EMPTY, (gateway(Script("   ")).run("p") as AiResult.Failure).kind)
    }

    @Test fun oneRequestAtATime() = runBlocking {
        var inFlight = 0
        var worst = 0
        val engine = object : OnDeviceAi {
            override val availability: StateFlow<AiAvailability> = MutableStateFlow(AiAvailability.Ready)
            override suspend fun refresh() = Unit
            override suspend fun download() = Unit
            override suspend fun generate(prompt: String, maxTokens: Int): AiResult {
                inFlight++; worst = maxOf(worst, inFlight); delay(20); inFlight--
                return AiResult.Text("x", 0)
            }
            override fun close() = Unit
        }
        val g = AiGateway(engine)
        kotlinx.coroutines.coroutineScope { repeat(5) { launch { g.run("p") } } }
        assertEquals(1, worst)
    }

    @Test fun aPhoneWithoutAModelAnswersAtOnceAndKeepsNoPrompt() = runBlocking {
        val g = AiGateway(NoOnDeviceAi)
        assertEquals(AiFailure.UNAVAILABLE, (g.run("secret text") as AiResult.Failure).kind)
        assertFalse("secret" in g.diagnostics.value.toString())
        assertTrue(g.diagnostics.value.line().startsWith("Last answer none"))
    }

    @Test fun diagnosticsHoldNumbersNotText() = runBlocking {
        val g = gateway(Script("hello there"))
        g.run("my private prompt")
        val line = g.diagnostics.value.line()
        assertTrue(line, line.contains("1 asked, 0 failed"))
        assertFalse("hello" in line || "private" in line)
    }

    // ---- understand ----

    @Test fun aSuggestionIsCheckedCachedAndNeverApplied() = runBlocking {
        val engine = Script(good); val cache = InMemoryAiCache()
        val u = AiUnderstander(gateway(engine), cache)
        val first = u.understand("AD-TEST", body) as Understanding.Suggestion
        assertEquals("1250.00", first.reading.amount)
        val again = u.understand("AD-TEST", body)
        assertTrue(again is Understanding.Suggestion)
        assertEquals("the second time comes from the cache", 1, engine.calls)
        assertEquals(1, cache.size)
    }

    @Test fun aHallucinatedAnswerBecomesNoSuggestion() = runBlocking {
        val u = AiUnderstander(gateway(Script("""{"kind":"spend","amount":"7777","merchant":"CAFE MOCHA","last4":null,"category":"Food"}""")), InMemoryAiCache())
        assertTrue(u.understand("AD-TEST", body) is Understanding.NoSuggestion)
    }

    @Test fun notRightIsRememberedAndTheModelIsNotAskedAgain() = runBlocking {
        val engine = Script(good, good); val u = AiUnderstander(gateway(engine), InMemoryAiCache())
        u.understand("AD-TEST", body)
        u.dismiss("AD-TEST", body)
        assertEquals(Understanding.Dismissed, u.understand("AD-TEST", body))
        assertEquals(1, engine.calls)
    }

    @Test fun aModelThatIsDownIsReportedNotHidden() = runBlocking {
        val u = AiUnderstander(gateway(Script(AiFailure.ERROR)), InMemoryAiCache())
        assertEquals(Understanding.Failed(AiFailure.ERROR), u.understand("AD-TEST", body))
    }

    // ---- batch ----

    private fun items(n: Int) = (1..n).map { BatchItem(it.toLong(), "AD-T", "Rs. ${it}00.00 spent at SHOP$it using card ending 4321") }

    @Test fun theBatchDoesAFewAtATimeAndReportsProgress() = runBlocking {
        val replies = (1..20).map { """{"kind":"spend","amount":"${it}00.00","merchant":"SHOP$it","last4":"4321","category":null}""" }
        val engine = Script(*replies.toTypedArray())
        val batch = AiBatch(AiUnderstander(gateway(engine), InMemoryAiCache()))
        batch.run(items(20), emptyMap()) { true }
        assertEquals(AiBatch.MAX_PER_RUN, engine.calls)
        assertEquals(AiBatch.MAX_PER_RUN, batch.progress.value.done)
        assertEquals(AiBatch.MAX_PER_RUN, batch.progress.value.found)
        assertFalse(batch.progress.value.running)
    }

    @Test fun theBatchStopsWhenThePhoneIsNoLongerCharging() = runBlocking {
        val engine = Script(*Array(5) { """{"kind":"other"}""" })
        val batch = AiBatch(AiUnderstander(gateway(engine), InMemoryAiCache()))
        var left = 2
        batch.run(items(5), emptyMap()) { left-- > 0 }
        assertEquals(2, engine.calls)
        assertTrue(batch.progress.value.stoppedBecause!!.contains("charging"))
    }

    @Test fun theBatchCanBeCancelled() = runBlocking {
        val engine = Script(*Array(5) { good }).also { it.slowMs = 200 }
        val batch = AiBatch(AiUnderstander(gateway(engine), InMemoryAiCache()))
        val job = launch { batch.run(items(5), emptyMap()) { true } }
        delay(50); job.cancel(); job.join()
        assertEquals("Stopped", batch.progress.value.stoppedBecause)
        assertFalse(batch.progress.value.running)
    }

    @Test fun theBatchSkipsWhatIsAlreadyKnown() = runBlocking {
        val cache = InMemoryAiCache(); val engine = Script(good)
        val u = AiUnderstander(gateway(engine), cache)
        u.dismiss("AD-T", items(2)[0].body)
        AiBatch(u).run(items(2), emptyMap()) { true }
        assertEquals(1, engine.calls)
    }

    // ---- the other features ----

    private val facts = MonthFacts(BigDecimal("12000"), BigDecimal("10000"), 20, "Food", BigDecimal("5000"), "Landlord", BigDecimal("8500"))

    @Test fun theMonthSentenceIsKeptOnlyWhenItPasses() = runBlocking {
        val cache = InMemoryAiCache()
        val ok = "You have spent ₹12,000 so far, 20% more than last month, mostly on Food."
        val w = AiMonthWriter(gateway(Script("You spent ₹9,999.", ok)), cache)
        assertNull("an invented figure is dropped", w.write(facts))
        assertEquals(0, cache.size)
        assertEquals(ok, AiMonthWriter(gateway(Script(ok)), cache).write(facts))
        assertEquals("then it is cached", ok, w.known(facts))
    }

    @Test fun aSummaryIsCachedPerMessageKey() = runBlocking {
        val c = SummaryCandidate(5, AiKeys.of("sum", "x"), "Dear customer, your order 88213 has been shipped and will arrive by Friday.")
        val engine = Script("Order 88213 shipped, arriving Friday")
        val s = AiSummarizer(gateway(engine), InMemoryAiCache())
        assertEquals("Order 88213 shipped, arriving Friday", s.summarize(c))
        assertEquals("Order 88213 shipped, arriving Friday", s.summarize(c))
        assertEquals(1, engine.calls)
    }

    @Test fun aCategoryIdeaMustBeOneOfTheAppsCategories() = runBlocking {
        assertEquals("Food", AiCategoryIdeas(gateway(Script("""{"category":"Food"}""")), InMemoryAiCache()).suggest("Cafe Coffee Day")?.name)
        assertNull(AiCategoryIdeas(gateway(Script("""{"category":"Spaceships"}""")), InMemoryAiCache()).suggest("Cafe Coffee Day"))
        assertNull(AiCategoryIdeas(gateway(Script("""{"category":"none"}""")), InMemoryAiCache()).suggest("R K Sharma"))
        assertNull("too short to say anything", AiCategoryIdeas(gateway(Script("""{"category":"Food"}""")), InMemoryAiCache()).suggest("ab"))
    }

    @Test fun theSummaryOverlayChangesOnlyTheHeadlineOfCardsThatHaveOne() {
        fun card(id: Long) = TriageCard(id, "s", "T", false, id, "head $id", "sum", emptyList(), TriageTone.NEUTRAL)
        val deck = TriageDeck(listOf(TriageStack("s", "S", TriageMode.KEEP, listOf(card(1), card(2)))), 2, 0, emptyList(), 0)
        val out = AiSummaryOverlay.apply(deck, mapOf(2L to "Better line"))
        assertEquals(listOf("head 1", "Better line"), out.stacks[0].items.map { it.headline })
        assertEquals("the same deck when nothing applies", deck, AiSummaryOverlay.apply(deck, emptyMap()))
    }

    // ---- gate ----

    @Test fun everythingIsOffUntilTheMasterSwitchAndTheExplanation() {
        val ready = AiAvailability.Ready; val ctx = AiContext()
        assertFalse(AiGate.allowed(AiFeature.ASK, AiSettings(), ready, ctx))
        val on = AiSettings(master = true, explained = true).withFeature(AiFeature.ASK, true)
        assertTrue(AiGate.allowed(AiFeature.ASK, on, ready, ctx))
        assertFalse("a feature that is not switched on", AiGate.allowed(AiFeature.MONTH, on, ready, ctx))
        assertFalse("not explained", AiGate.allowed(AiFeature.ASK, on.copy(explained = false), ready, ctx))
        assertFalse("master off", AiGate.allowed(AiFeature.ASK, on.copy(master = false), ready, ctx))
    }

    @Test fun demoModeAndUnsupportedPhonesNeverRunAndHideTheSurfaces() {
        val on = AiSettings(true, true, AiFeature.entries.toSet())
        assertFalse(AiGate.allowed(AiFeature.ASK, on, AiAvailability.Ready, AiContext(demo = true)))
        assertFalse(AiGate.visible(AiFeature.ASK, on, AiAvailability.Ready, AiContext(demo = true)))
        assertFalse(AiGate.visible(AiFeature.ASK, on, AiAvailability.Unsupported, AiContext()))
        assertFalse(AiGate.allowed(AiFeature.ASK, on, AiAvailability.NeedsDownload, AiContext()))
        assertFalse(AiGate.allowed(AiFeature.ASK, on, AiAvailability.Downloading(0.3f), AiContext()))
        assertTrue("shown, so the person can see why it waits", AiGate.visible(AiFeature.ASK, on, AiAvailability.NeedsDownload, AiContext()))
    }

    @Test fun batchWorkNeedsChargingUnlessAllowedAndNeverLite() {
        val on = AiSettings(true, true, setOf(AiFeature.UNDERSTAND))
        val ok = AiContext(charging = true)
        assertTrue(AiGate.batchAllowed(on, AiAvailability.Ready, ok))
        assertFalse(AiGate.batchAllowed(on, AiAvailability.Ready, ok.copy(lite = true)))
        assertFalse(AiGate.batchAllowed(on, AiAvailability.Ready, ok.copy(charging = false)))
        assertTrue("battery allowed by the person", AiGate.batchAllowed(on.copy(batchOnlyWhileCharging = false), AiAvailability.Ready, ok.copy(charging = false)))
        assertFalse(AiGate.backgroundAllowed(AiFeature.UNDERSTAND, on, AiAvailability.Ready, ok.copy(lite = true)))
    }

    @Test fun theCacheIsBoundedAndKeysDoNotContainText() {
        val cache: AiCache = InMemoryAiCache(max = 3)
        for (i in 1..5) cache.put("k$i", "v")
        assertEquals(3, cache.size)
        assertNull(cache.get("k1"))
        val key = AiKeys.of("und", "my secret message 4321")
        assertFalse("secret" in key || "4321" in key)
        cache.clear(); assertEquals(0, cache.size)
    }
}
