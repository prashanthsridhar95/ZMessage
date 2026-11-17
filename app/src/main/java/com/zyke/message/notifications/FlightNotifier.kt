package com.zyke.message.notifications

import com.zyke.message.R
import com.zyke.message.domain.flight.FlightNotificationText
import com.zyke.message.presentation.flights.FlightRequests

/**
 * The flight notifications: the shared [TripNotifier] dressed with the flight's channels ("Flight tracking", "Flight reminders"),
 * icon, ids and words. The ongoing notice is not dismissible and quiet, the finished ones are dismissible, the lock screen follows the
 * one Settings switch; all of that is in [TripNotifier]. Nothing here is logged.
 */
object FlightNotifier : TripNotifier() {
    override val look = TripLook(
        trackingChannel = TRACKING_CHANNEL,
        legacyTrackingChannel = "flight_tracking",
        promptChannel = PROMPT_CHANNEL,
        trackingName = "Flight tracking",
        trackingDescription = "Your flight while it is in the air, and when it lands. Shown on the lock screen; no sound, vibration or pop-up.",
        promptName = "Flight reminders",
        promptDescription = "A reminder shortly before a flight, and a button to start live tracking when the phone does not let the app start it by itself",
        icon = R.drawable.baseline_flight_24,
        baseId = 4300,
        pausedId = PAUSED_ID,
        placeholderId = PLACEHOLDER_ID,
        publicTitle = FlightNotificationText.PUBLIC_TITLE,
        publicText = FlightNotificationText.PUBLIC_TEXT,
        finishedPublicTitle = "Flight update",
        promptPublicTitle = "Your flight",
        pausedPublicTitle = "Flight update",
        placeholderTitle = "Flight tracking",
        placeholderText = "Checking your flight",
        openAction = FlightRequests.ACTION_OPEN,
        openExtra = FlightRequests.EXTRA_KEY,
        serviceClass = FlightTrackingService::class.java,
        stopAction = FlightTrackingService.ACTION_STOP,
        stopExtra = FlightTrackingService.EXTRA_KEY,
        paused = { FlightNotificationText.paused() }
    )

    /**
     * A new id: a channel's importance and lock-screen visibility cannot be changed by the app once created, and the first
     * version of the channel was LOW importance with private lock-screen content (removed when found).
     */
    const val TRACKING_CHANNEL = "flight_live"
    const val PROMPT_CHANNEL = "flight_prompts"
    const val PAUSED_ID = 4299
    const val PLACEHOLDER_ID = 4298
}
