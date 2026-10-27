package com.zyke.message.domain.model

data class AppSettings(
    /** Blocks screenshots and hides the app's content in the recent-apps switcher. */
    val screenProtection: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Off by default: when on, brand logos for well-known names are downloaded once and kept on the phone. */
    val fetchLogos: Boolean = false
)
