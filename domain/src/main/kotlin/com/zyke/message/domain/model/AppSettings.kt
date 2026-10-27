package com.zyke.message.domain.model

data class AppSettings(
    /** Blocks screenshots and hides the app's content in the recent-apps switcher. */
    val screenProtection: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)
