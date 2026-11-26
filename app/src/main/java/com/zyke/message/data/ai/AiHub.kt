package com.zyke.message.data.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.zyke.message.data.perf.LiteRuntime
import com.zyke.message.domain.ai.AiAsker
import com.zyke.message.domain.ai.AiAvailability
import com.zyke.message.domain.ai.AiBatch
import com.zyke.message.domain.ai.AiCache
import com.zyke.message.domain.ai.AiCategory
import com.zyke.message.domain.ai.AiCategoryIdeas
import com.zyke.message.domain.ai.AiContext
import com.zyke.message.domain.ai.AiDigestWriter
import com.zyke.message.domain.ai.AiDiagnostics
import com.zyke.message.domain.ai.AiFailure
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiGate
import com.zyke.message.domain.ai.AiExplainer
import com.zyke.message.domain.ai.AiGateway
import com.zyke.message.domain.ai.AiMonthWriter
import com.zyke.message.domain.ai.AiPayeeNamer
import com.zyke.message.domain.ai.AiPrompts
import com.zyke.message.domain.ai.AiResult
import com.zyke.message.domain.ai.AiSettings
import com.zyke.message.domain.ai.AiSettingsRepository
import com.zyke.message.domain.ai.AiSummarizer
import com.zyke.message.domain.ai.AiSummarySource
import com.zyke.message.domain.ai.AiUnderstander
import com.zyke.message.domain.ai.AiUnusualWriter
import com.zyke.message.domain.ai.AiWhyWriter
import com.zyke.message.domain.ai.AskOutcome
import com.zyke.message.domain.ai.AskQuery
import com.zyke.message.domain.ai.BatchItem
import com.zyke.message.domain.ai.BatchProgress
import com.zyke.message.domain.ai.InMemoryAiCache
import com.zyke.message.domain.ai.InMemoryAiSettings
import com.zyke.message.domain.ai.ModelReport
import com.zyke.message.domain.ai.ModelWatch
import com.zyke.message.domain.ai.Phrased
import com.zyke.message.domain.ai.MonthFacts
import com.zyke.message.domain.ai.NoOnDeviceAi
import com.zyke.message.domain.ai.OnDeviceAi
import com.zyke.message.domain.ai.SummaryCandidate
import com.zyke.message.domain.ai.Understanding
import com.zyke.message.domain.ai.UnusualFacts
import com.zyke.message.domain.ai.WhyFacts
import com.zyke.message.domain.ai.DigestFacts
import com.zyke.message.domain.model.CustomCategory
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The on-device AI as the screens use it: one place that owns the model, the single request line, the cache, the switches and the
 * five features. Nothing here runs until a screen asks, and nothing runs in Demo mode (the engine is [NoOnDeviceAi] and every
 * gate says no) or from the SMS receiver (this class is only built from the container, which the receiver never touches for it).
 * No prompt or answer is logged.
 */
class AiHub(
    private val context: Context,
    val demo: Boolean,
    private val scope: CoroutineScope,
    private val customCategories: () -> List<CustomCategory> = { emptyList() }
) {
    private val engine: OnDeviceAi = if (demo) NoOnDeviceAi else GeminiNanoEngine(ModelEvidenceStore(context))
    val settingsStore: AiSettingsRepository = if (demo) InMemoryAiSettings() else AiSettingsRepositoryImpl(context)
    private val cache: AiCache = if (demo) InMemoryAiCache() else PrefsAiCache(context)
    private val gateway = AiGateway(engine)
    val understander = AiUnderstander(gateway, cache)
    private val asker = AiAsker(gateway)
    private val monthWriter = AiMonthWriter(gateway, cache)
    private val summarizer = AiSummarizer(gateway, cache)
    private val categoryIdeas = AiCategoryIdeas(gateway, cache)
    private val payeeNamer = AiPayeeNamer(gateway, cache)
    private val explainer = AiExplainer(gateway, cache)
    private val whyWriter = AiWhyWriter(gateway, cache)
    private val digestWriter = AiDigestWriter(gateway, cache)
    private val unusualWriter = AiUnusualWriter(gateway, cache)
    private val batch = AiBatch(understander)

    val settings: StateFlow<AiSettings> get() = settingsStore.settings
    val availability: StateFlow<AiAvailability> get() = engine.availability
    val report: StateFlow<ModelReport> get() = engine.report
    val diagnostics: StateFlow<AiDiagnostics> get() = gateway.diagnostics
    val working: StateFlow<Boolean> get() = gateway.working
    val batchProgress: StateFlow<BatchProgress> get() = batch.progress

    private val _kept = MutableStateFlow(cache.size)
    /** How many suggestions are kept on this phone. */
    val kept: StateFlow<Int> = _kept.asStateFlow()

    fun update(change: (AiSettings) -> AiSettings) = settingsStore.update(change)

    /** The person's own category names (lower case) to their ids, for the guard. */
    fun customs(): Map<String, String> = customCategories().associate { it.name.trim().lowercase() to it.id }

    /** The person's own category ids to their names, for showing a grouped answer. */
    fun customNames(): Map<String, String> = customCategories().associate { it.id to it.name }

    /** The moment as the gates see it. [charging] is read from the phone only when asked for (the battery check is a system call). */
    fun aiContext(charging: Boolean = false): AiContext =
        AiContext(demo = demo, lite = LiteRuntime.isLite, charging = charging, modelWorked = ModelWatch.worked(report.value, System.currentTimeMillis()))

    fun allowed(feature: AiFeature): Boolean = AiGate.allowed(feature, settings.value, availability.value, aiContext())

    fun visible(feature: AiFeature): Boolean = AiGate.visible(feature, settings.value, availability.value, aiContext())

    private fun isCharging(): Boolean = runCatching {
        val status = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    /** Asks the phone whether the model is there. Cheap; the settings page does it when it opens. */
    fun refresh() {
        if (!demo) scope.launch { engine.refresh() }
    }

    fun download() {
        if (!demo) scope.launch { engine.download() }
    }

    // ---- warming up ----

    private var warmJob: Job? = null
    private var warmTriedAtMs: Long? = null

    /**
     * One tiny request (a word in, one token out) so the first real answer of this app start is not the one that wakes the model.
     * Optional and quiet: only with the switch on, never in Demo or lite mode, once per app start, at most every few minutes, and it
     * stays out of the page's numbers. [cancelWarmUp] stops it. The SMS receiver never reaches this class.
     */
    fun warmUp() {
        val s = settings.value
        val now = System.currentTimeMillis()
        if (!ModelWatch.shouldWarmUp(s.master && s.explained, aiContext(), availability.value, report.value, warmTriedAtMs, now)) return
        if (warmJob?.isActive == true) return
        warmTriedAtMs = now
        warmJob = scope.launch { gateway.run(AiPrompts.free(WARM_PROMPT), maxTokens = WARM_TOKENS, timeoutMs = WARM_TIMEOUT_MS, retries = 0, quiet = true) }
    }

    fun cancelWarmUp() {
        warmJob?.cancel()
    }

    // ---- feature 1 and 5 ----

    suspend fun understand(sender: String, body: String): Understanding =
        if (allowed(AiFeature.UNDERSTAND)) understander.understand(sender, body, customs()).also { _kept.value = cache.size }
        else Understanding.Failed(AiFailure.UNAVAILABLE)

    fun dismiss(sender: String, body: String) {
        understander.dismiss(sender, body)
        _kept.value = cache.size
    }

    suspend fun categoryIdea(payee: String): AiCategory? =
        if (allowed(AiFeature.CATEGORIES)) categoryIdeas.suggest(payee, customs()).also { _kept.value = cache.size } else null

    fun startBatch(items: List<BatchItem>) {
        if (!allowed(AiFeature.UNDERSTAND) || batchJob?.isActive == true) return
        batchJob = scope.launch {
            batch.run(items, customs()) { AiGate.batchAllowed(settings.value, availability.value, aiContext(isCharging())) }
            _kept.value = cache.size
        }
    }

    private var batchJob: Job? = null

    fun stopBatch() {
        batchJob?.cancel()
    }

    // ---- feature 3 ----

    /** [previous] is the request the chat answered last, so a follow-up ("and last month?") can change part of it. */
    suspend fun ask(question: String, payees: Collection<String>, today: LocalDate, previous: AskQuery? = null): AskOutcome =
        if (allowed(AiFeature.ASK)) asker.understand(question, payees, customs(), today, previous) else AskOutcome.Failed(AiFailure.UNAVAILABLE)

    // ---- feature 4 ----

    /** The verified sentence for [facts] if it is already known, without asking the model. */
    fun knownMonthSentence(facts: MonthFacts): String? = if (allowed(AiFeature.MONTH)) monthWriter.known(facts) else null

    suspend fun monthSentence(facts: MonthFacts): String? =
        if (allowed(AiFeature.MONTH)) monthWriter.write(facts).also { _kept.value = cache.size } else null

    // ---- features 6 to 10: each answers one tap (or, for the digest line, one preparation while the app is open) ----

    private val off = Phrased.Failed(AiFailure.UNAVAILABLE)

    /** The tidy name already worked out for [raw], without asking the model. */
    fun knownName(raw: String): String? = if (allowed(AiFeature.NAMES)) payeeNamer.known(raw) else null

    suspend fun suggestName(raw: String): Phrased =
        if (allowed(AiFeature.NAMES)) payeeNamer.suggest(raw).also { _kept.value = cache.size } else off

    fun knownExplanation(sender: String, body: String): String? = if (allowed(AiFeature.EXPLAIN)) explainer.known(sender, body) else null

    suspend fun explain(sender: String, body: String): Phrased =
        if (allowed(AiFeature.EXPLAIN)) explainer.explain(sender, body).also { _kept.value = cache.size } else off

    /** Whether the explanation of this message may be asked for at all (never for a one-time code). */
    fun canExplain(message: com.zyke.message.domain.model.ClassifiedMessage): Boolean = explainer.eligible(message)

    fun knownWhy(facts: WhyFacts): String? = if (allowed(AiFeature.WHY_UP)) whyWriter.known(facts) else null

    suspend fun why(facts: WhyFacts): Phrased =
        if (allowed(AiFeature.WHY_UP)) whyWriter.explain(facts).also { _kept.value = cache.size } else off

    fun knownUnusual(facts: UnusualFacts): String? = if (allowed(AiFeature.UNUSUAL)) unusualWriter.known(facts) else null

    suspend fun unusual(facts: UnusualFacts): Phrased =
        if (allowed(AiFeature.UNUSUAL)) unusualWriter.explain(facts).also { _kept.value = cache.size } else off

    private var digestJob: Job? = null

    /**
     * Prepares tomorrow's digest line while the app is open: background work (never in lite mode), one at a time, a short time
     * limit and no retry. The morning notification only reads what is stored here; it never asks. Safe to call often.
     */
    fun prepareDigest(facts: DigestFacts) {
        if (!AiGate.backgroundAllowed(AiFeature.DIGEST, settings.value, availability.value, aiContext())) return
        if (digestJob?.isActive == true || digestWriter.known(facts) != null || working.value) return
        digestJob = scope.launch {
            digestWriter.prepare(facts)
            _kept.value = cache.size
        }
    }

    // ---- feature 2 ----

    private val summaryMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val summaryQueue = Channel<SummaryCandidate>(SUMMARY_QUEUE)
    private val queued = HashSet<Long>()
    private var summaryJob: Job? = null

    /** Home's rewritten summaries. Candidates are answered from the cache at once; the rest wait in a short queue, a few at a time. */
    val summaries: AiSummarySource = object : AiSummarySource {
        override val summaries: StateFlow<Map<Long, String>> = summaryMap.asStateFlow()

        override fun request(candidates: List<SummaryCandidate>) {
            if (!AiGate.backgroundAllowed(AiFeature.SUMMARIES, settings.value, availability.value, aiContext())) return
            val ready = candidates.mapNotNull { c -> summarizer.known(c)?.let { c.messageId to it } }.toMap()
            if (ready.isNotEmpty()) summaryMap.value = summaryMap.value + ready
            candidates.filter { it.messageId !in summaryMap.value }.forEach { c ->
                val fresh = synchronized(queued) { queued.add(c.messageId) }
                if (fresh && summaryQueue.trySend(c).isFailure) synchronized(queued) { queued.remove(c.messageId) }
            }
            if (summaryJob?.isActive != true) summaryJob = scope.launch { drainSummaries() }
        }
    }

    private suspend fun drainSummaries() {
        for (candidate in summaryQueue) {
            synchronized(queued) { queued.remove(candidate.messageId) }
            if (!AiGate.backgroundAllowed(AiFeature.SUMMARIES, settings.value, availability.value, aiContext())) continue
            val text = summarizer.summarize(candidate) ?: continue
            summaryMap.value = summaryMap.value + (candidate.messageId to text)
            _kept.value = cache.size
            delay(SUMMARY_GAP_MS)
        }
    }

    // ---- the settings page ----

    /**
     * The "Try it" box: the person's own words to the model, with only a length limit. Shown on that page and nowhere else.
     * It ignores the status on purpose: whether an answer comes is the real test, and a success flips the status to Ready.
     */
    suspend fun tryIt(text: String): AiResult {
        val s = settings.value
        val ok = !demo && s.master && s.explained
        return if (ok) gateway.run(AiPrompts.free(text), maxTokens = TRY_TOKENS, ignoreStatus = true) else AiResult.Failure(AiFailure.UNAVAILABLE)
    }

    /** Forgets every suggestion, the rewritten summaries and the numbers on the page. */
    fun clearCache() {
        cache.clear()
        summaryMap.value = emptyMap()
        gateway.resetDiagnostics()
        batch.reset()
        _kept.value = 0
    }

    fun close() {
        digestJob?.cancel()
        stopBatch()
        cancelWarmUp()
        engine.close()
    }

    private companion object {
        const val SUMMARY_QUEUE = 24
        const val SUMMARY_GAP_MS = 400L
        const val TRY_TOKENS = 120
        const val WARM_PROMPT = "Hi"
        const val WARM_TOKENS = 2
        const val WARM_TIMEOUT_MS = 20_000L
    }
}
