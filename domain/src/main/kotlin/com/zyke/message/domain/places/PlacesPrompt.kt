package com.zyke.message.domain.places

/** Which version of the "Remember where you spend" page to show, if any. */
enum class PlacesPromptKind { NONE, ONBOARDING, UPDATE }

/** The pages of the prompt: the short offer, the plain consent, then the second permission step. */
enum class PlacesPromptPhase { OFFER, CONSENT, ALL_THE_TIME }

/**
 * When and with what words the app offers spending places. The feature is never switched on by this: only the person's tap on
 * Continue (after the consent page) does that, and the system permissions are asked only after it. Pure, so it is tested.
 */
object PlacesPrompt {
    /** Anyone who answered before, or already engaged with the switch, is not asked again. */
    fun answered(s: SpendPlaceSettings): Boolean = s.prompted || s.enabled || s.explained

    /**
     * [fresh] is a new install; otherwise an update or a restore. [blocked] is true while something else owns the screen
     * (welcome, the lock screen, a tour). Demo mode never asks.
     */
    fun kind(settings: SpendPlaceSettings, demo: Boolean, fresh: Boolean, blocked: Boolean): PlacesPromptKind = when {
        demo || blocked || answered(settings) -> PlacesPromptKind.NONE
        fresh -> PlacesPromptKind.ONBOARDING
        else -> PlacesPromptKind.UPDATE
    }

    fun kicker(kind: PlacesPromptKind): String? = if (kind == PlacesPromptKind.UPDATE) "NEW" else null
    fun headline(kind: PlacesPromptKind): String = if (kind == PlacesPromptKind.UPDATE) "New: remember where you spend" else "Remember where you spend"

    const val PROMISE = "One quick location fix when a purchase text arrives. Kept only on this phone, never uploaded. You can delete it any time."
    val POINTS = listOf(
        "Rounded to about 11 metres, once per purchase text",
        "No tracking in between",
        "Online shops, bills and money coming in are skipped"
    )
    const val TURN_ON = "Turn on"
    const val NOT_NOW = "Not now"
    const val FOOTNOTE = "You can change this any time in Settings, Spending places"

    const val CONSENT_HEADLINE = "Before you allow"
    const val CONSENT_PROMISE = "When a text says you paid at a shop, the app asks the phone where it is, one time, and saves it."
    val CONSENT_POINTS = listOf(
        "Only on this phone. Never uploaded, never written to the log, left out of backups unless you choose.",
        "One position per purchase text, rounded to about 11 metres. No tracking in between.",
        "Texts arrive when the app is closed, so Android will ask for \"Allow all the time\". It is used for that one position only.",
        "Online shops, bills, card payments and money coming in are skipped. You can delete places any time."
    )
    const val CONTINUE = "Continue"

    /** The page after consent when only the "all the time" step is left; the words come from [PlaceHealth]. */
    const val LATER = "Do this later"
}
