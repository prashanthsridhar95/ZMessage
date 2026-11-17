package com.zyke.message.notifications

import com.zyke.message.R
import com.zyke.message.domain.train.TrainNotificationText
import com.zyke.message.presentation.trains.TrainRequests

/**
 * The train notifications: the shared [TripNotifier] dressed with the train's own channels ("Train tracking", "Train reminders"),
 * icon (the Live Update uses R.drawable.baseline_train_24 too), ids and words. The ongoing notice is not dismissible and quiet, the
 * finished ones are dismissible, the lock screen follows the one Settings switch the flight service keeps; all of that is in
 * [TripNotifier]. Nothing here is logged.
 */
object TrainNotifier : TripNotifier() {
    override val look = TripLook(
        trackingChannel = TRACKING_CHANNEL,
        legacyTrackingChannel = null,
        promptChannel = PROMPT_CHANNEL,
        trackingName = "Train tracking",
        trackingDescription = "Your train while it is under way, and when it arrives. Shown on the lock screen; no sound, vibration or pop-up.",
        promptName = "Train reminders",
        promptDescription = "A reminder shortly before a train, and a button to start live tracking when the phone does not let the app start it by itself",
        icon = R.drawable.baseline_train_24,
        baseId = 5300,
        pausedId = PAUSED_ID,
        placeholderId = PLACEHOLDER_ID,
        publicTitle = TrainNotificationText.PUBLIC_TITLE,
        publicText = TrainNotificationText.PUBLIC_TEXT,
        finishedPublicTitle = "Train update",
        promptPublicTitle = "Your train",
        pausedPublicTitle = "Train update",
        placeholderTitle = "Train tracking",
        placeholderText = "Checking your train",
        openAction = TrainRequests.ACTION_OPEN,
        openExtra = TrainRequests.EXTRA_KEY,
        serviceClass = TrainTrackingService::class.java,
        stopAction = TrainTrackingService.ACTION_STOP,
        stopExtra = TrainTrackingService.EXTRA_KEY,
        paused = { TrainNotificationText.paused() }
    )

    /** Its own channel ids, so the person can mute trains apart from flights (a channel's settings cannot be changed afterwards). */
    const val TRACKING_CHANNEL = "train_live"
    const val PROMPT_CHANNEL = "train_prompts"
    const val PAUSED_ID = 5299
    const val PLACEHOLDER_ID = 5298
}
