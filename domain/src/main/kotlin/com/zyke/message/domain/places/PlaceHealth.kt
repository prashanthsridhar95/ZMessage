package com.zyke.message.domain.places

/** What the phone allows right now, as plain facts the app reads from Android. */
data class PlaceFacts(
    val switchedOn: Boolean,
    val readingPaused: Boolean,
    val canHearTexts: Boolean,
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    /** Android 10 and later need the "all the time" step; before that, foreground permission covers a receiver. */
    val needsBackgroundStep: Boolean,
    val locationServicesOn: Boolean,
    val receiverOn: Boolean
)

/** The steps of the calm two-step flow, in the order worth fixing. */
enum class PlaceProblem { SWITCHED_OFF, READING_PAUSED, CANNOT_HEAR_TEXTS, NEEDS_LOCATION, NEEDS_ALL_THE_TIME, LOCATION_SERVICES_OFF, RECEIVER_OFF }

object PlaceHealth {
    fun problem(f: PlaceFacts): PlaceProblem? = when {
        !f.switchedOn -> PlaceProblem.SWITCHED_OFF
        f.readingPaused -> PlaceProblem.READING_PAUSED
        !f.canHearTexts -> PlaceProblem.CANNOT_HEAR_TEXTS
        !f.fineLocation -> PlaceProblem.NEEDS_LOCATION
        f.needsBackgroundStep && !f.backgroundLocation -> PlaceProblem.NEEDS_ALL_THE_TIME
        !f.locationServicesOn -> PlaceProblem.LOCATION_SERVICES_OFF
        !f.receiverOn -> PlaceProblem.RECEIVER_OFF
        else -> null
    }

    /** The card's heading: Off, Allow location, Allow all the time, On, or the first thing in the way. */
    fun title(p: PlaceProblem?): String = when (p) {
        null -> "On"
        PlaceProblem.SWITCHED_OFF -> "Off"
        PlaceProblem.READING_PAUSED -> "Paused: reading is paused"
        PlaceProblem.CANNOT_HEAR_TEXTS -> "Cannot hear new texts"
        PlaceProblem.NEEDS_LOCATION -> "Allow location"
        PlaceProblem.NEEDS_ALL_THE_TIME -> "Allow all the time"
        PlaceProblem.LOCATION_SERVICES_OFF -> "Location is switched off"
        PlaceProblem.RECEIVER_OFF -> "Not listening yet"
    }

    fun detail(p: PlaceProblem?): String = when (p) {
        null -> "A purchase text that arrives now gets one quick location fix, kept only on this phone."
        PlaceProblem.SWITCHED_OFF -> ""
        PlaceProblem.READING_PAUSED -> "Reading messages is paused in Privacy and security, so new texts are not seen."
        PlaceProblem.CANNOT_HEAR_TEXTS -> "Allow SMS access so a purchase can be noticed the moment it arrives."
        PlaceProblem.NEEDS_LOCATION -> "Step 1 of 2: let the app use this phone's location."
        PlaceProblem.NEEDS_ALL_THE_TIME -> "Step 2 of 2: choose \"Allow all the time\" (needed because texts arrive when the app is closed). Location is read once per purchase text, never in between."
        PlaceProblem.LOCATION_SERVICES_OFF -> "Switch Location on in the phone's quick settings."
        PlaceProblem.RECEIVER_OFF -> "The part that hears new texts is switched off."
    }

    fun action(p: PlaceProblem?): String? = when (p) {
        null, PlaceProblem.SWITCHED_OFF -> null
        PlaceProblem.READING_PAUSED -> "Resume reading"
        PlaceProblem.CANNOT_HEAR_TEXTS -> "Allow SMS access"
        PlaceProblem.NEEDS_LOCATION -> "Allow location"
        PlaceProblem.NEEDS_ALL_THE_TIME -> "Allow all the time"
        PlaceProblem.LOCATION_SERVICES_OFF -> "Open location settings"
        PlaceProblem.RECEIVER_OFF -> "Fix"
    }
}
