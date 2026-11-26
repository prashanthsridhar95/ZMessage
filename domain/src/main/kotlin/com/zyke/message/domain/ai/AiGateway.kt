package com.zyke.message.domain.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the settings page may say about how the model has been doing. Numbers and a failure kind only, never a prompt or an answer. */
data class AiDiagnostics(
    val lastLatencyMs: Long? = null,
    val lastFailure: AiFailure? = null,
    val requests: Int = 0,
    val failures: Int = 0
) {
    /** One quiet line, safe to show and to put in a report. */
    fun line(): String = when {
        requests == 0 -> "No requests yet"
        else -> "Last answer " + (lastLatencyMs?.let { "%.1f s".format(it / MS_PER_S) } ?: "none") +
            " · last problem: " + (lastFailure?.plain ?: "none") + " · $requests asked, $failures failed"
    }

    private companion object { const val MS_PER_S = 1000.0 }
}

/**
 * Puts every request to the model through one line: a single request at a time, each with a time limit, a cap on the
 * answer, and a wait-and-retry when the phone says it is busy or the app is over its quota. It is the only caller of
 * [OnDeviceAi.generate]. It never logs or keeps a prompt.
 *
 * @param pause how to wait (a test passes a fake that records instead of waiting)
 */
class AiGateway(
    private val engine: OnDeviceAi,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::nanoTime,
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    private val lock = Mutex()
    private val _diagnostics = MutableStateFlow(AiDiagnostics())
    val diagnostics: StateFlow<AiDiagnostics> = _diagnostics

    private val _working = MutableStateFlow(false)

    /** True while a request is waiting for or in the model. */
    val working: StateFlow<Boolean> = _working

    val availability: StateFlow<AiAvailability> get() = engine.availability

    /**
     * Runs one request. Waits its turn; gives up after [timeoutMs] of its own turn; tries again after a wait while the
     * failure says it is worth it, at most [retries] times. A [quiet] request (the warm-up) leaves the numbers alone. Cancelling the caller cancels the request.
     */
    suspend fun run(
        prompt: String, maxTokens: Int = DEFAULT_TOKENS, timeoutMs: Long = DEFAULT_TIMEOUT_MS, retries: Int = DEFAULT_RETRIES,
        ignoreStatus: Boolean = false, quiet: Boolean = false
    ): AiResult {
        // A recent real answer beats the status word: try, and let the engine's own failure say if it is not there.
        val usable = engine.availability.value.canGenerate || ModelWatch.worked(engine.report.value, wallClock())
        if (!ignoreStatus && !usable) return record(AiResult.Failure(AiFailure.UNAVAILABLE), quiet)
        return lock.withLock {
            _working.value = true
            try {
                var attempt = 0
                var result = once(prompt, maxTokens, timeoutMs)
                while (result is AiResult.Failure && result.kind.retryable && attempt < retries) {
                    pause(BACKOFF_MS shl attempt)
                    attempt++
                    result = once(prompt, maxTokens, timeoutMs)
                }
                record(result, quiet)
            } finally {
                _working.value = false
            }
        }
    }

    private suspend fun once(prompt: String, maxTokens: Int, timeoutMs: Long): AiResult {
        val started = clock()
        val answer = try {
            withTimeoutOrNull(timeoutMs) { engine.generate(prompt, maxTokens.coerceIn(1, MAX_TOKENS)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            AiResult.Failure(AiFailure.ERROR)
        }
        val elapsed = (clock() - started) / NANOS_PER_MS
        return when {
            answer == null -> AiResult.Failure(AiFailure.TIMEOUT, elapsed)
            answer is AiResult.Text && answer.text.isBlank() -> AiResult.Failure(AiFailure.EMPTY, elapsed)
            answer is AiResult.Text -> answer.copy(latencyMs = elapsed)
            else -> answer
        }
    }

    private fun record(result: AiResult, quiet: Boolean = false): AiResult {
        if (quiet) return result
        val before = _diagnostics.value
        _diagnostics.value = when (result) {
            is AiResult.Text -> before.copy(lastLatencyMs = result.latencyMs, lastFailure = null, requests = before.requests + 1)
            is AiResult.Failure -> before.copy(lastFailure = result.kind, requests = before.requests + 1, failures = before.failures + 1)
        }
        return result
    }

    /** Forgets the numbers (Settings does this with the cache). */
    fun resetDiagnostics() {
        _diagnostics.value = AiDiagnostics()
    }

    companion object {
        const val DEFAULT_TOKENS = 128
        const val MAX_TOKENS = 512
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val DEFAULT_RETRIES = 2
        const val BACKOFF_MS = 1_500L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
