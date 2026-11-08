package com.zyke.message.domain.device

/** What the phone currently allows, as plain facts the app reads from Android. */
data class CodeAlertFacts(
    /** The person's own switch for the one-time-code and card-bill alerts. */
    val switchedOn: Boolean,
    val readingPaused: Boolean,
    /** Permission to hear about new texts (RECEIVE_SMS). */
    val canHearTexts: Boolean,
    /** Notifications for this app are allowed at all: the runtime permission on newer Android, and the app-wide switch. */
    val notificationsAllowed: Boolean,
    /** The code alerts' own channel has been turned off or set to silent-and-hidden in system settings. */
    val channelOff: Boolean
)

/** Why a one-time-code alert would not reach the person, in the order worth fixing. */
enum class CodeAlertProblem { SWITCHED_OFF, READING_PAUSED, CANNOT_HEAR_TEXTS, NOTIFICATIONS_BLOCKED, CHANNEL_OFF }

object CodeAlertHealth {
    /** The first thing standing in the way, or null when a code that arrives now would be announced. */
    fun problem(facts: CodeAlertFacts): CodeAlertProblem? = when {
        !facts.switchedOn -> CodeAlertProblem.SWITCHED_OFF
        facts.readingPaused -> CodeAlertProblem.READING_PAUSED
        !facts.canHearTexts -> CodeAlertProblem.CANNOT_HEAR_TEXTS
        !facts.notificationsAllowed -> CodeAlertProblem.NOTIFICATIONS_BLOCKED
        facts.channelOff -> CodeAlertProblem.CHANNEL_OFF
        else -> null
    }
}

/** What the phone allows for passing codes on to trusted people. */
data class CodeSharingFacts(
    val switchedOn: Boolean,
    val people: Int,
    val paused: Boolean,
    val readingPaused: Boolean,
    /** RECEIVE_SMS: needed to hear the code arrive (the same receiver as the code alerts). */
    val canHearTexts: Boolean,
    /** SEND_SMS. */
    val canSendTexts: Boolean,
    /** The receiver is switched on in the phone's component settings. */
    val receiverOn: Boolean
)

/** Why a code would not be passed on, in the order worth fixing. */
enum class CodeSharingProblem { SWITCHED_OFF, NOBODY_ADDED, READING_PAUSED, PAUSED, CANNOT_HEAR_TEXTS, CANNOT_SEND_TEXTS, RECEIVER_OFF }

object CodeSharingHealth {
    /** The first thing standing in the way, or null when a code that arrives now would be passed on. */
    fun problem(facts: CodeSharingFacts): CodeSharingProblem? = when {
        !facts.switchedOn -> CodeSharingProblem.SWITCHED_OFF
        facts.people == 0 -> CodeSharingProblem.NOBODY_ADDED
        facts.readingPaused -> CodeSharingProblem.READING_PAUSED
        facts.paused -> CodeSharingProblem.PAUSED
        !facts.canHearTexts -> CodeSharingProblem.CANNOT_HEAR_TEXTS
        !facts.canSendTexts -> CodeSharingProblem.CANNOT_SEND_TEXTS
        !facts.receiverOn -> CodeSharingProblem.RECEIVER_OFF
        else -> null
    }
}
