package com.zyke.message.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The things the on-device model can be asked to do. Each has its own switch; the master switch is above all of them. */
enum class AiFeature(val title: String, val summary: String) {
    UNDERSTAND("Understand unclear messages", "Suggest what a message not understood means"),
    SUMMARIES("Smarter summaries", "Clearer one-line summaries on Home"),
    ASK("Ask your money", "Answer questions typed in Search"),
    MONTH("Month in a sentence", "One or two sentences on Insights"),
    CATEGORIES("Category ideas", "Suggest a category for a payee with none")
}

/** The person's choices. Everything is off until the master switch is turned on after reading [explained]. */
data class AiSettings(
    val master: Boolean = false,
    /** The one-time explanation was shown and accepted. */
    val explained: Boolean = false,
    val enabled: Set<AiFeature> = emptySet(),
    /** The review-pile check waits for the charger (the default); the person can allow it on battery. */
    val batchOnlyWhileCharging: Boolean = true
) {
    fun isOn(feature: AiFeature): Boolean = master && explained && feature in enabled

    fun withFeature(feature: AiFeature, on: Boolean): AiSettings = copy(enabled = if (on) enabled + feature else enabled - feature)
}

interface AiSettingsRepository {
    val settings: StateFlow<AiSettings>
    fun update(change: (AiSettings) -> AiSettings)
}

/** In memory only: Demo mode and tests. */
class InMemoryAiSettings(initial: AiSettings = AiSettings()) : AiSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AiSettings> = state
    override fun update(change: (AiSettings) -> AiSettings) { state.value = change(state.value) }
}

/** What the app knows about the moment a request would be made. */
data class AiContext(
    val demo: Boolean = false,
    val lite: Boolean = false,
    val charging: Boolean = false,
    val readingPaused: Boolean = false
)

/** One rule for "may this feature run now", used by every surface, so they cannot disagree. */
object AiGate {
    /** A feature may run when it is switched on, the model is ready and this is not Demo mode. */
    fun allowed(feature: AiFeature, settings: AiSettings, availability: AiAvailability, context: AiContext): Boolean =
        !context.demo && settings.isOn(feature) && availability.canGenerate

    /** Whether to show the feature's surface at all: hidden in Demo mode, off, or on a phone that cannot do it. */
    fun visible(feature: AiFeature, settings: AiSettings, availability: AiAvailability, context: AiContext): Boolean =
        !context.demo && settings.isOn(feature) && availability !is AiAvailability.Unsupported

    /** The review-pile batch: never in lite mode, and on the charger unless the person allowed battery. */
    fun batchAllowed(settings: AiSettings, availability: AiAvailability, context: AiContext): Boolean =
        allowed(AiFeature.UNDERSTAND, settings, availability, context) && !context.lite &&
            (context.charging || !settings.batchOnlyWhileCharging)

    /** Background work that nobody asked for right now (summaries of Home cards): only a few, never in lite mode. */
    fun backgroundAllowed(feature: AiFeature, settings: AiSettings, availability: AiAvailability, context: AiContext): Boolean =
        allowed(feature, settings, availability, context) && !context.lite
}
