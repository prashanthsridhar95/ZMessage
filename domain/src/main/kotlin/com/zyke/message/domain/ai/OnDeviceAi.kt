package com.zyke.message.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the language model on this phone can be used right now. The app shows exactly these states; the library that
 * answers them lives in one file (`GeminiNanoEngine`), so nothing else depends on it.
 */
sealed interface AiAvailability {
    /** This phone has no model (not a supported phone, or Google's AI Core service is missing or has not set it up). */
    data object Unsupported : AiAvailability

    /** The phone could fetch the model, but has not. Needs the person to ask for it. */
    data object NeedsDownload : AiAvailability

    /** The model is being fetched by Google's service. [progress] is 0..1 when known. */
    data class Downloading(val progress: Float?) : AiAvailability

    data object Ready : AiAvailability

    /** An answer is being worked out, or the phone told the app to wait (quota or background limit). */
    data object Busy : AiAvailability

    data class Failed(val reason: String) : AiAvailability

    val canGenerate: Boolean get() = this is Ready || this is Busy
}

/** Why an answer did not come. Never carries the prompt or the message. */
enum class AiFailure(val plain: String, val retryable: Boolean) {
    UNAVAILABLE("The model is not available on this phone", false),
    BUSY("The phone is busy with another request", true),
    QUOTA("The phone limits how often an app may ask; try again later", true),
    TIMEOUT("It took too long", false),
    CANCELLED("Stopped", false),
    EMPTY("The model gave no answer", false),
    ERROR("The model could not answer", false)
}

/** What a request came to. [Text] holds the model's own words, to be checked before anything is shown. */
sealed interface AiResult {
    data class Text(val text: String, val latencyMs: Long) : AiResult
    data class Failure(val kind: AiFailure, val latencyMs: Long = 0L) : AiResult
}

/**
 * The one door to a language model that runs on this phone. The domain knows only this contract. There is no cloud
 * implementation and none is allowed: a phone without a model uses [NoOnDeviceAi] and the app works as it always did.
 */
interface OnDeviceAi {
    val availability: StateFlow<AiAvailability>

    /** Asks the phone again whether the model is there (cheap). */
    suspend fun refresh()

    /** Asks the phone's AI service to fetch the model, and returns when it is done or failed. Progress shows in [availability]. */
    suspend fun download()

    /** One request. [maxTokens] caps the answer; the caller times it out (see [AiGateway]). Never throws. */
    suspend fun generate(prompt: String, maxTokens: Int): AiResult

    /** Lets go of the model's memory. */
    fun close()
}

/** For a phone, a build or a mode (Demo) with no model: every request says so at once. */
object NoOnDeviceAi : OnDeviceAi {
    override val availability: StateFlow<AiAvailability> = MutableStateFlow(AiAvailability.Unsupported)
    override suspend fun refresh() = Unit
    override suspend fun download() = Unit
    override suspend fun generate(prompt: String, maxTokens: Int): AiResult = AiResult.Failure(AiFailure.UNAVAILABLE)
    override fun close() = Unit
}
