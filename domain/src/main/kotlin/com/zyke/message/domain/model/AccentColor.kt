package com.zyke.message.domain.model

/**
 * The colour the app is tinted with. [DEFAULT] keeps the phone's own wallpaper colours where the phone has them (the
 * fixed purple below is only the fallback); the others are fixed. Each has a primary and the colour of text on it for
 * Day, and for Night and True black (which share one pair), all chosen to read at WCAG AA (4.5:1).
 * Colours are 0xAARRGGBB.
 */
enum class AccentColor(
    val label: String,
    val lightPrimary: Long,
    val lightOnPrimary: Long,
    val darkPrimary: Long,
    val darkOnPrimary: Long
) {
    DEFAULT("Default", 0xFF6650A4, 0xFFFFFFFF, 0xFFD0BCFF, 0xFF381E72),
    BLUE("Blue", 0xFF1A5FB4, 0xFFFFFFFF, 0xFFA8C8FF, 0xFF00315F),
    GREEN("Green", 0xFF1B6D3A, 0xFFFFFFFF, 0xFF8FD99B, 0xFF00391B),
    ORANGE("Orange", 0xFF9A4A00, 0xFFFFFFFF, 0xFFFFB77C, 0xFF4E2600),
    ROSE("Rose", 0xFFA5254F, 0xFFFFFFFF, 0xFFFFB1C4, 0xFF5F0A27),
    TEAL("Teal", 0xFF006A6A, 0xFFFFFFFF, 0xFF4CDADA, 0xFF003737);

    companion object {
        /** The accent stored under [name]; anything missing or unknown is [DEFAULT]. */
        fun fromStored(name: String?): AccentColor = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
