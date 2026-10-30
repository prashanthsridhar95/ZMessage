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
    val readingPaused: Boolean = false
)
