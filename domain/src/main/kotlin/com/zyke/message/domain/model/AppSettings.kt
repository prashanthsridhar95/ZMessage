package com.zyke.message.domain.model

data class AppSettings(
    /** Blocks screenshots and hides the app's content in the recent-apps switcher. */
    val screenProtection: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** When on, brand logos for well-known names are downloaded once and kept on the phone. */
    val fetchLogos: Boolean = true,
    /** Asks for the phone's fingerprint, face or screen lock when the app is opened after being away. */
    val appLock: Boolean = false,
    /** A short notification each morning with what is due and what is on. */
    val morningDigest: Boolean = false,
    /** Off until chosen: a "Copy code" or "Pay" notification the moment a code or bill arrives. */
    val liveAlerts: Boolean = false,
    /** Stops reading the phone's messages and listening for new ones; what was read stays and can still be used. */
    val readingPaused: Boolean = false,
    /** Off until chosen: one notification on Sunday morning with the week's spending and what is due next. */
    val weeklySummary: Boolean = false,
    /** Off until chosen: a quiet heads-up when a new payment is far above usual or leaves an account low. Needs live alerts. */
    val headsUp: Boolean = false,
    /** An account balance below this (stated by a new payment) triggers a heads-up; null means no floor. */
    val lowBalanceFloor: java.math.BigDecimal? = null,
    /** Shows the big money figures as dots until tapped. */
    val hideAmounts: Boolean = false,
    /** Showing hidden amounts asks for the phone's fingerprint, face or screen lock (when the phone has one). */
    val authForAmounts: Boolean = true,
    /** How long hidden amounts stay shown once asked for. */
    val revealDuration: com.zyke.message.domain.privacy.RevealDuration = com.zyke.message.domain.privacy.RevealDuration.DEFAULT,
    val accent: AccentColor = AccentColor.DEFAULT,
    /** The seed (0xFFRRGGBB) behind [AccentColor.CUSTOM]; null when none has been chosen. */
    val customAccent: Long? = null,
    /** Which launcher icon colour is enabled (see [AppIconChoice]); the manifest state follows this. */
    val appIcon: AppIconChoice = AppIconChoice.DEFAULT,
    /** How much motion and polish the app spends; Automatic goes lite on phones with little memory (see [LiteMode]). */
    val performanceMode: PerformanceMode = PerformanceMode.AUTOMATIC
)
