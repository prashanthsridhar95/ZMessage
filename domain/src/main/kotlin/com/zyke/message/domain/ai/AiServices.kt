package com.zyke.message.domain.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** The result of asking the model about one message. */
sealed interface Understanding {
    data class Suggestion(val reading: CheckedReading) : Understanding

    /** The model answered but the text does not back it up (or it said nothing is there). */
    data class NoSuggestion(val reason: String) : Understanding

    data class Failed(val kind: AiFailure) : Understanding

    /** The person said "Not right" earlier. */
    data object Dismissed : Understanding
}

/** Feature 1: reads a message the rules did not understand. Nothing it returns is applied; the person confirms. */
class AiUnderstander(private val gateway: AiGateway, private val cache: AiCache) {
    fun keyOf(sender: String, body: String) = AiKeys.of("und", "$sender|$body")
    private fun noKey(sender: String, body: String) = AiKeys.of("no", "$sender|$body")

    /** What is already known without asking the model; null when the model has to be asked. */
    fun known(sender: String, body: String, customs: Map<String, String> = emptyMap()): Understanding? {
        if (cache.get(noKey(sender, body)) != null) return Understanding.Dismissed
        val reply = cache.get(keyOf(sender, body)) ?: return null
        return judge(reply, body, customs)
    }

    suspend fun understand(sender: String, body: String, customs: Map<String, String> = emptyMap()): Understanding {
        known(sender, body, customs)?.let { return it }
        return when (val result = gateway.run(AiPrompts.understand(body), maxTokens = TOKENS)) {
            is AiResult.Failure -> Understanding.Failed(result.kind)
            is AiResult.Text -> {
                if (AiSuggestionGuard.parseReading(result.text) != null) cache.put(keyOf(sender, body), result.text.take(MAX_STORED))
                judge(result.text, body, customs)
            }
        }
    }

    /** "Not right": remembered, so the same message is not suggested (or batch-checked) again. */
    fun dismiss(sender: String, body: String) {
        cache.put(noKey(sender, body), "1")
        cache.remove(keyOf(sender, body))
    }

    private fun judge(reply: String, body: String, customs: Map<String, String>): Understanding {
        val raw = AiSuggestionGuard.parseReading(reply) ?: return Understanding.NoSuggestion("The answer was not usable")
        if (!raw.kind.movesMoney && raw.kind != ReadingKind.BILL) return Understanding.NoSuggestion("It does not look like a payment")
        return when (val g = AiSuggestionGuard.check(raw, body, customs)) {
            is Guarded.Accepted -> Understanding.Suggestion(g.value)
            is Guarded.Rejected -> Understanding.NoSuggestion(g.reason)
        }
    }

    private companion object {
        const val TOKENS = 96
        const val MAX_STORED = 600
    }
}

/** Feature 5: a category for a payee that has none. The payee's name is never changed; only a category from the app's list is accepted. */
class AiCategoryIdeas(private val gateway: AiGateway, private val cache: AiCache) {
    suspend fun suggest(payee: String, customs: Map<String, String> = emptyMap()): AiCategory? {
        val name = payee.trim()
        if (name.length < MIN_NAME) return null
        val key = AiKeys.of("cat", name.lowercase())
        val reply = cache.get(key) ?: when (val r = gateway.run(AiPrompts.category(name), maxTokens = TOKENS)) {
            is AiResult.Text -> r.text.also { if (MiniJson.firstObject(it) != null) cache.put(key, it.take(MAX_STORED)) }
            is AiResult.Failure -> return null
        }
        return AiSuggestionGuard.category(MiniJson.firstObject(reply)?.text("category"), customs)
    }

    private companion object {
        const val MIN_NAME = 3
        const val TOKENS = 40
        const val MAX_STORED = 200
    }
}

/** What asking a question came to. */
sealed interface AskOutcome {
    data class Understood(val query: AskQuery) : AskOutcome
    data class Refused(val reason: String) : AskOutcome
    data class Failed(val kind: AiFailure) : AskOutcome
}

/** Feature 3, first half: the model turns the question into a query from a fixed vocabulary. The answer is worked out elsewhere, by code. */
class AiAsker(private val gateway: AiGateway) {
    /**
     * Turns [question] into a request. With a [previous] request the model is first asked to read it as a follow-up; when that reading
     * is not usable the question is read fresh, on its own, so unclear context never blocks a question that stands alone.
     */
    suspend fun understand(
        question: String, knownPayees: Collection<String>, customs: Map<String, String>, today: LocalDate, previous: AskQuery? = null
    ): AskOutcome {
        val q = question.trim()
        if (q.length < MIN_QUESTION) return AskOutcome.Refused("Type a little more")
        if (previous != null) {
            when (val r = gateway.run(AiPrompts.askFollowUp(q, today, previous), maxTokens = TOKENS)) {
                is AiResult.Failure -> return AskOutcome.Failed(r.kind)
                is AiResult.Text -> (AskFollowUp.parse(r.text, q, knownPayees, customs, previous) as? Guarded.Accepted)?.let { return AskOutcome.Understood(it.value) }
            }
        }
        return when (val r = gateway.run(AiPrompts.ask(q, today), maxTokens = TOKENS)) {
            is AiResult.Failure -> AskOutcome.Failed(r.kind)
            is AiResult.Text -> when (val parsed = AskParser.parse(r.text, q, knownPayees, customs)) {
                is Guarded.Accepted -> AskOutcome.Understood(parsed.value)
                is Guarded.Rejected -> AskOutcome.Refused(parsed.reason)
            }
        }
    }

    private companion object {
        const val MIN_QUESTION = 4
        const val TOKENS = 100
    }
}

/** Feature 4: phrases the month from figures the app computed, and shows the result only if it uses no other figure. */
class AiMonthWriter(private val gateway: AiGateway, private val cache: AiCache) {
    /** The verified sentence for [facts] if already known. */
    fun known(facts: MonthFacts): String? = cache.get(facts.key)?.takeIf { AiMonth.verify(it, facts) != null }

    suspend fun write(facts: MonthFacts): String? {
        known(facts)?.let { return it }
        val r = gateway.run(AiPrompts.monthSentence(facts.lines), maxTokens = TOKENS) as? AiResult.Text ?: return null
        val ok = AiMonth.verify(r.text, facts) ?: return null
        cache.put(facts.key, ok)
        return ok
    }

    private companion object { const val TOKENS = 90 }
}

/** Feature 2: one clearer line for a message whose rule-based line is only its first words. */
class AiSummarizer(private val gateway: AiGateway, private val cache: AiCache) {
    fun known(candidate: SummaryCandidate): String? = cache.get(candidate.key)?.takeIf { AiSummaryGuard.accept(it, candidate.body) != null }

    suspend fun summarize(candidate: SummaryCandidate): String? {
        known(candidate)?.let { return it }
        val r = gateway.run(AiPrompts.summary(candidate.body), maxTokens = TOKENS) as? AiResult.Text ?: return null
        val ok = AiSummaryGuard.accept(r.text, candidate.body) ?: return null
        cache.put(candidate.key, ok)
        return ok
    }

    private companion object { const val TOKENS = 40 }
}

/** The review-pile run: where it stands. */
data class BatchProgress(
    val total: Int = 0,
    val done: Int = 0,
    val found: Int = 0,
    val running: Boolean = false,
    /** Why it stopped early, in plain words; null when it finished or was never started. */
    val stoppedBecause: String? = null
)

/** One message of the review pile. */
data class BatchItem(val id: Long, val sender: String, val body: String)

/**
 * "Check my review pile": works through a few messages one after another, only while [mayContinue] says the phone is
 * charging and idle. It stores what it finds in the cache; nothing is applied. It can be stopped at any moment.
 */
class AiBatch(private val understander: AiUnderstander) {
    private val _progress = MutableStateFlow(BatchProgress())
    val progress: StateFlow<BatchProgress> = _progress

    suspend fun run(items: List<BatchItem>, customs: Map<String, String>, mayContinue: () -> Boolean) {
        val todo = items.filter { understander.known(it.sender, it.body, customs) == null }.take(MAX_PER_RUN)
        _progress.value = BatchProgress(total = todo.size, running = true)
        var found = 0
        var stopped: String? = null
        try {
            for ((index, item) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!mayContinue()) { stopped = "The phone is no longer charging and idle"; break }
                when (val u = understander.understand(item.sender, item.body, customs)) {
                    is Understanding.Suggestion -> found++
                    is Understanding.Failed -> if (u.kind.retryable || u.kind == AiFailure.UNAVAILABLE) { stopped = u.kind.plain; break }
                    else -> Unit
                }
                _progress.value = _progress.value.copy(done = index + 1, found = found)
            }
        } catch (e: CancellationException) {
            _progress.value = _progress.value.copy(running = false, stoppedBecause = "Stopped")
            throw e
        }
        _progress.value = _progress.value.copy(running = false, found = found, stoppedBecause = stopped)
    }

    fun reset() { _progress.value = BatchProgress() }

    companion object { const val MAX_PER_RUN = 12 }
}
