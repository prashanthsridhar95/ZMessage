package com.zyke.message.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.zyke.message.domain.ai.AiAvailability
import com.zyke.message.domain.ai.AiFailure
import com.zyke.message.domain.ai.AiResult
import com.zyke.message.domain.ai.DownloadEvent
import com.zyke.message.domain.ai.ModelReport
import com.zyke.message.domain.ai.ModelWatch
import com.zyke.message.domain.ai.OnDeviceAi
import com.zyke.message.domain.ai.PhoneModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ONLY file that touches ML Kit GenAI (`com.google.mlkit:genai-prompt`), which talks to Gemini Nano through Android's
 * AI Core service on the phone. Everything else in the app uses [OnDeviceAi]. A test (AiPolicyTest) keeps it that way.
 *
 * What this file does not do: no network call of its own (Google's service fetches and updates the model; see PRIVACY.md),
 * no logging of a prompt or an answer, no cloud fallback, and nothing in the SMS receiver. It is only ever built outside Demo mode.
 *
 * A real answer is kept (two numbers, see [ModelEvidenceStore]) and beats a "not downloaded" status word; a download stream that shows
 * nothing for 30 s is dropped. UNVERIFIED: written from the library's documentation without being compiled or run against a real phone. The calls used are
 * Generation.getClient(), checkStatus(), download() (a Flow of DownloadStatus), generateContent(request) and close().
 */
class GeminiNanoEngine(private val evidence: ModelEvidenceStore) : OnDeviceAi {
    private val state = MutableStateFlow<AiAvailability>(AiAvailability.Unsupported)
    override val availability: StateFlow<AiAvailability> = state.asStateFlow()

    private val facts = MutableStateFlow(ModelReport(workedAtMs = evidence.workedAtMs(), workedLatencyMs = evidence.latencyMs()))
    override val report: StateFlow<ModelReport> = facts.asStateFlow()

    private fun note(change: (ModelReport) -> ModelReport) = facts.update(change)

    private fun now(): Long = System.currentTimeMillis()

    /** What the phone's word and our own evidence add up to: a recent real answer beats "not downloaded". */
    private fun settled(fromPhone: AiAvailability): AiAvailability = ModelWatch.effective(fromPhone, facts.value, now())

    /** Megabytes already seen, kept while the status flips between checks so the page does not lose them. */
    private fun seenMegabytes(): Int? = facts.value.megabytes

    private var model: GenerativeModel? = null

    @Synchronized
    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    override suspend fun refresh() {
        if (state.value is AiAvailability.Downloading && downloading) return
        var seen = PhoneModelStatus.ERROR
        val fromPhone = try {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> AiAvailability.Ready.also { seen = PhoneModelStatus.READY }
                FeatureStatus.DOWNLOADABLE -> AiAvailability.NeedsDownload.also { seen = PhoneModelStatus.NOT_DOWNLOADED }
                FeatureStatus.DOWNLOADING -> AiAvailability.Downloading(null, seenMegabytes()).also { seen = PhoneModelStatus.DOWNLOADING }
                else -> AiAvailability.Unsupported.also { seen = PhoneModelStatus.UNSUPPORTED }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A phone without AI Core (or without Google Play services) lands here: say it is not supported, with the kind of problem.
            if (e is GenAiException) AiAvailability.Failed(plainReason(e)) else AiAvailability.Unsupported.also { seen = PhoneModelStatus.UNSUPPORTED }
        }
        note { it.copy(phoneStatus = seen, checkedAtMs = now(), askedNotStarted = it.askedNotStarted && seen == PhoneModelStatus.NOT_DOWNLOADED) }
        state.value = settled(fromPhone)
    }

    @Volatile private var downloading = false

    override suspend fun download() {
        if (downloading) return
        downloading = true
        note { it.copy(streamActive = true, streamStartedAtMs = now(), lastEvent = DownloadEvent.NONE, askedNotStarted = false) }
        try {
            state.value = AiAvailability.Downloading(null, seenMegabytes())
            coroutineScope {
                val collector = launch { collectDownload() }
                // A stream that shows nothing at all is not "Downloading": let go of it and ask the phone what it says.
                val watchdog = launch {
                    delay(ModelWatch.STREAMLESS_CAP_MS)
                    if (ModelWatch.streamlessExpired(facts.value, now())) collector.cancel()
                }
                collector.join()
                watchdog.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            note { it.copy(lastEvent = DownloadEvent.ERROR) }
            state.value = AiAvailability.Failed(if (e is GenAiException) plainReason(e) else "The download did not finish")
        } finally {
            downloading = false
            note { it.copy(streamActive = false) }
        }
        // Trust the phone's own answer over what the stream said.
        refresh()
        // Still "not downloaded" and the stream never said a word: be honest that the phone did not start.
        if (state.value == AiAvailability.NeedsDownload && facts.value.lastEvent == DownloadEvent.NONE) note { it.copy(askedNotStarted = true) }
    }

    private suspend fun collectDownload() {
        client().download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadProgress -> {
                    val mb = (status.totalBytesDownloaded / BYTES_PER_MB).toInt()
                    state.value = AiAvailability.Downloading(null, mb)
                    note { it.copy(lastEvent = DownloadEvent.PROGRESS, megabytes = mb, lastProgressAtMs = now()) }
                }
                is DownloadStatus.DownloadFailed -> {
                    state.value = AiAvailability.Failed("The download did not finish")
                    note { it.copy(lastEvent = DownloadEvent.FAILED) }
                }
                DownloadStatus.DownloadCompleted -> {
                    state.value = AiAvailability.Ready
                    note { it.copy(lastEvent = DownloadEvent.COMPLETED) }
                }
                else -> note { it.copy(lastEvent = DownloadEvent.OTHER) }
            }
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): AiResult {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / NANOS_PER_MS
        var answered = false
        // The first request after the app starts also wakes the model, which can take several seconds.
        note { it.copy(warmingUp = !it.warmedUp) }
        return try {
            // Only a model the app believes is ready shows as Busy; otherwise the status stays as it was until an answer proves it.
            if (state.value.canGenerate) state.value = AiAvailability.Busy
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = TEMPERATURE
                topK = TOP_K
                candidateCount = 1
                maxOutputTokens = maxTokens
            }
            val answer = client().generateContent(request).candidates.firstOrNull()?.text
            if (answer.isNullOrBlank()) {
                AiResult.Failure(AiFailure.EMPTY, elapsed())
            } else {
                // An answer is the real proof the model is there, whatever the status said. Keep the proof on this phone.
                val at = now()
                val took = elapsed()
                answered = true
                evidence.save(at, took)
                note { it.copy(workedAtMs = at, workedLatencyMs = took, askedNotStarted = false) }
                state.value = ModelWatch.afterSuccessfulAnswer(state.value)
                AiResult.Text(answer, took)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val kind = failureOf(e)
            // "Not available" from the model itself is the one failure that withdraws the proof.
            if (kind == AiFailure.UNAVAILABLE) forgetProof()
            AiResult.Failure(kind, elapsed())
        } finally {
            note { it.copy(warmingUp = false, warmedUp = it.warmedUp || answered) }
            if (state.value is AiAvailability.Busy) state.value = AiAvailability.Ready
        }
    }

    private suspend fun forgetProof() {
        evidence.forget()
        note { it.copy(workedAtMs = null, workedLatencyMs = null) }
        refresh()
    }

    @Synchronized
    override fun close() {
        runCatching { model?.close() }
        model = null
    }

    /** Sorted by what the exception says, not by constants whose names may change between versions. The text is never shown or kept. */
    private fun failureOf(e: Exception): AiFailure {
        val said = (e.message.orEmpty() + " " + e.javaClass.simpleName).lowercase()
        return when {
            "quota" in said -> AiFailure.QUOTA
            "busy" in said || "background" in said -> AiFailure.BUSY
            "not_available" in said || "not available" in said || "unavailable" in said -> AiFailure.UNAVAILABLE
            else -> AiFailure.ERROR
        }
    }

    private fun plainReason(e: GenAiException): String = when (failureOf(e)) {
        AiFailure.UNAVAILABLE -> "The phone's AI service is not available"
        AiFailure.BUSY, AiFailure.QUOTA -> "The phone's AI service is busy"
        else -> "The phone's AI service gave an error"
    }

    private companion object {
        const val TEMPERATURE = 0.1f
        const val TOP_K = 10
        const val BYTES_PER_MB = 1_048_576L
        const val NANOS_PER_MS = 1_000_000L
    }
}
