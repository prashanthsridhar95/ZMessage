package com.zyke.message.domain.onboarding

/** How a dot in the progress row is drawn. */
enum class DotState { DONE, CURRENT, UPCOMING }

/**
 * The progress dots of a short flow (a permission asked one at a time, a tour). Nothing loops and nothing animates by
 * itself: the row is a function of which step the person is on.
 */
data class StepDots(val total: Int, val current: Int) {
    /** A row of dots is only worth drawing for a flow of more than one step. */
    val visible: Boolean get() = total > 1

    private val at: Int get() = current.coerceIn(0, (total - 1).coerceAtLeast(0))

    val states: List<DotState>
        get() = List(total.coerceAtLeast(0)) { i -> if (i < at) DotState.DONE else if (i == at) DotState.CURRENT else DotState.UPCOMING }

    /** What a screen reader says in place of the dots. */
    val spoken: String get() = "Step ${at + 1} of $total"
}

/**
 * The questions the app may ask the phone, one page each. Only [SMS_READ] is needed to use the app; the others are asked
 * when the feature that needs them is switched on, and each can be declined without losing anything else.
 */
enum class PermissionStep(
    val headline: String,
    val promise: String,
    val allow: String,
    val skip: String?,
    val required: Boolean
) {
    SMS_READ(
        "Your money, from your messages",
        "Reads the SMS already on this phone to find payments and bills. Nothing leaves the phone.",
        "Allow SMS access", null, true
    ),
    NOTIFICATIONS(
        "Hear about it the moment it happens",
        "A quiet note for a bill that is due or a code that arrives.",
        "Allow notifications", "Not now", false
    ),
    NOTIFICATION_ACCESS(
        "Include chats and top-ups",
        "Optional. Lets the app see payment notifications from Google and Samsung Messages.",
        "Open notification access", "Skip", false
    ),
    SMS_SEND(
        "Pass a code on",
        "Only to the people you chose, and only when you switch code sharing on.",
        "Allow sending texts", "Not now", false
    );

    companion object {
        /** The steps to ask in order, given which are still unanswered; the required one always leads. */
        fun pending(missing: Set<PermissionStep>): List<PermissionStep> = entries.filter { it in missing }
    }
}
