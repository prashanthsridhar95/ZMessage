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
import com.zyke.message.domain.ai.OnDeviceAi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ONLY file that touches ML Kit GenAI (`com.google.mlkit:genai-prompt`), which talks to Gemini Nano through Android's
 * AI Core service on the phone. Everything else in the app uses [OnDeviceAi]. A test (AiPolicyTest) keeps it that way.
 *
 * What this file does not do: no network call of its own (Google's service fetches and updates the model; see PRIVACY.md),
 * no logging of a prompt or an answer, no cloud fallback, and nothing in the SMS receiver. It is only ever built outside Demo mode.
 *
 * UNVERIFIED: written from the library's documentation without being compiled or run against a real phone. The calls used are
 * Generation.getClient(), checkStatus(), download() (a Flow of DownloadStatus), generateContent(request) and close().
 */
class GeminiNanoEngine : OnDeviceAi {
    private val state = MutableStateFlow<AiAvailability>(AiAvailability.Unsupported)
    override val availability: StateFlow<AiAvailability> = state.asStateFlow()

    private var model: GenerativeModel? = null

    @Synchronized
    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    override suspend fun refresh() {
        if (state.value is AiAvailability.Downloading && downloading) return
        state.value = try {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> AiAvailability.Ready
                FeatureStatus.DOWNLOADABLE -> AiAvailability.NeedsDownload
                FeatureStatus.DOWNLOADING -> AiAvailability.Downloading(null)
                else -> AiAvailability.Unsupported
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A phone without AI Core (or without Google Play services) lands here: say it is not supported, with the kind of problem.
            if (e is GenAiException) AiAvailability.Failed(plainReason(e)) else AiAvailability.Unsupported
        }
    }

    @Volatile private var downloading = false

    override suspend fun download() {
        if (downloading) return
        downloading = true
        try {
            state.value = AiAvailability.Downloading(null)
            client().download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadProgress ->
                        state.value = AiAvailability.Downloading(null, (status.totalBytesDownloaded / BYTES_PER_MB).toInt())
                    is DownloadStatus.DownloadFailed -> state.value = AiAvailability.Failed("The download did not finish")
                    DownloadStatus.DownloadCompleted -> state.value = AiAvailability.Ready
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            state.value = AiAvailability.Failed(if (e is GenAiException) plainReason(e) else "The download did not finish")
        } finally {
            downloading = false
        }
        // Trust the phone's own answer over what the stream said.
        refresh()
    }

    override suspend fun generate(prompt: String, maxTokens: Int): AiResult {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / NANOS_PER_MS
        return try {
            state.value = AiAvailability.Busy
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = TEMPERATURE
                topK = TOP_K
                candidateCount = 1
                maxOutputTokens = maxTokens
            }
            val answer = client().generateContent(request).candidates.firstOrNull()?.text
            if (answer.isNullOrBlank()) AiResult.Failure(AiFailure.EMPTY, elapsed()) else AiResult.Text(answer, elapsed())
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            AiResult.Failure(failureOf(e), elapsed())
        } finally {
            if (state.value is AiAvailability.Busy) state.value = AiAvailability.Ready
        }
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
