package com.zyke.message.domain.email

/** Which version of the "Catch payments that arrive by email" page to show, if any. */
enum class EmailPromptKind { NONE, ONBOARDING, UPDATE }

/** The pages of the prompt: the short offer, the plain consent, then the form that connects a mailbox. */
enum class EmailPromptPhase { OFFER, CONSENT, CONNECT }

/**
 * When and with what words the app offers to read bank alert emails, modelled on the spending places prompt. The feature is never switched
 * on by this: only a successful Connect (the sign-in checked, the password kept) does that, and the form is the one in Settings. Asked once
 * ever: any way of leaving it, "Not now", back or a swipe, is an answer. Pure, so it is tested.
 */
object EmailPrompt {
    /** Anyone who answered before, is connected, or already engaged with the switch is not asked. */
    fun answered(s: EmailAlertSettings): Boolean = s.emailPrompted || s.connected || s.enabled

    /**
     * [fresh] is a new install; otherwise an update or a restore. [blocked] is true while something else owns the screen (welcome, the lock
     * screen, another onboarding page, a tour). Demo mode never asks.
     */
    fun kind(settings: EmailAlertSettings, demo: Boolean, fresh: Boolean, blocked: Boolean): EmailPromptKind = when {
        demo || blocked || answered(settings) -> EmailPromptKind.NONE
        fresh -> EmailPromptKind.ONBOARDING
        else -> EmailPromptKind.UPDATE
    }

    fun headline(kind: EmailPromptKind): String = if (kind == EmailPromptKind.UPDATE) "New: email alerts" else "Catch payments that arrive by email"

    const val PROMISE = "Some banks are making small UPI alerts (under Rs 500) email-only. With your OK, the app reads those alert emails, only on this phone."
    val POINTS = listOf(
        "Reads only emails from listed bank alert addresses; nothing else in your mailbox",
        "Read-only. Nothing leaves the phone except talking to your mail provider, securely",
        "Your app password stays only on this phone. Disconnect any time"
    )
    const val SET_UP = "Set up"
    const val NOT_NOW = "Not now"
    const val FOOTNOTE = "You can change this any time in Settings, Email alerts"

    const val CONSENT_HEADLINE = "Before you connect"
    const val CONSENT_PROMISE = "You sign in to your mail provider with an app password you make for this, never your normal password."
    val CONSENT_POINTS = listOf(
        "Only emails from the bank addresses listed in Settings are looked for and read. Every other email is never fetched.",
        "Nothing is marked read, moved or deleted. The emails are read on the phone and never sent anywhere.",
        "The app password is sealed on this phone, never backed up and never logged. Disconnect forgets it.",
        "The wording of bank emails is not checked against real ones: what the app does not understand is shown, masked, under Messages not understood."
    )
    const val CONTINUE = "Continue"

    const val CONNECT_HEADLINE = "Connect your mailbox"
}
