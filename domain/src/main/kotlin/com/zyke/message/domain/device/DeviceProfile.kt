package com.zyke.message.domain.device

/**
 * A pretend phone for the developer's "Simulate a small phone": a window of [widthDp] x [heightDp] (0 = the real
 * screen's size on that side), drawn at [densityScale] times the real density with text at [fontScale] times the real
 * font scale. Pure data, so the presets are tested.
 */
data class DeviceProfile(
    val id: String,
    val label: String,
    val widthDp: Int = 0,
    val heightDp: Int = 0,
    val densityScale: Float = 1f,
    val fontScale: Float = 1f
) {
    /** True for the profile that changes nothing. */
    val isIdentity: Boolean get() = widthDp == 0 && heightDp == 0 && densityScale == 1f && fontScale == 1f

    /** A short line for the "Simulating ..." chip. */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (widthDp > 0 && heightDp > 0) parts += "${widthDp}×${heightDp} dp" else if (widthDp > 0) parts += "$widthDp dp wide"
        if (densityScale != 1f) parts += "density ×${trim(densityScale)}"
        if (fontScale != 1f) parts += "font ×${trim(fontScale)}"
        return if (parts.isEmpty()) "off" else parts.joinToString(", ")
    }

    companion object {
        val OFF = DeviceProfile("off", "Off")
        val REDMI_9A = DeviceProfile("redmi9a", "Redmi 9A-like (360×745 dp, font 1.15)", 360, 745, 1f, 1.15f)
        val REDMI_NOTE = DeviceProfile("redminote", "Redmi Note-like (393×830 dp)", 393, 830)
        val SMALL_320 = DeviceProfile("small320", "Small 320 dp", 320, 640)

        val PRESETS: List<DeviceProfile> = listOf(REDMI_9A, REDMI_NOTE, SMALL_320)

        val DENSITY_SCALES = listOf(1.0f, 1.15f, 1.3f)
        val FONT_SCALES = listOf(1.0f, 1.15f, 1.3f, 1.5f)

        /** A profile that only scales density and font on top of [base]'s window size. */
        fun custom(densityScale: Float, fontScale: Float, base: DeviceProfile = OFF): DeviceProfile =
            base.copy(id = "custom", label = "Custom", densityScale = densityScale.coerceIn(0.5f, 2f), fontScale = fontScale.coerceIn(0.5f, 2f))

        /** Writes [profile] as one short string for preferences. */
        fun encode(profile: DeviceProfile): String =
            listOf(profile.id, profile.widthDp, profile.heightDp, profile.densityScale, profile.fontScale).joinToString("|")

        /** The profile written by [encode]; anything missing or malformed is [OFF]. A preset id always wins over stored numbers. */
        fun decode(stored: String?): DeviceProfile {
            if (stored.isNullOrBlank()) return OFF
            val parts = stored.split('|')
            if (parts.size != 5) return OFF
            if (parts[0] == OFF.id) return OFF
            val preset = PRESETS.firstOrNull { it.id == parts[0] }
            if (preset != null) return preset
            val w = parts[1].toIntOrNull() ?: return OFF
            val h = parts[2].toIntOrNull() ?: return OFF
            val d = parts[3].toFloatOrNull() ?: return OFF
            val f = parts[4].toFloatOrNull() ?: return OFF
            if (w < 0 || h < 0) return OFF
            return DeviceProfile("custom", "Custom", w, h, d.coerceIn(0.5f, 2f), f.coerceIn(0.5f, 2f))
        }

        private fun trim(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    }
}

/**
 * What the layout code asks about the screen. [widthDp] and [heightDp] are the window in dp, [fontScale] the text
 * scale. Pure, so the thresholds are tested and the same everywhere.
 */
data class ScreenClass(val widthDp: Int, val heightDp: Int, val fontScale: Float) {
    /** Narrower than a typical phone (under 360 dp): tight spacing, stacked rows. */
    val isCompact: Boolean get() = widthDp < COMPACT_WIDTH_DP

    /** Short window (under 640 dp), e.g. landscape or a split screen. */
    val isShort: Boolean get() = heightDp < SHORT_HEIGHT_DP

    /** Large text (1.3 or more): side-by-side rows should stack. */
    val isLargeText: Boolean get() = fontScale >= LARGE_TEXT_SCALE

    /** Rows of two things that each want room should stack: narrow, or large text on anything under 411 dp. */
    val stackRows: Boolean get() = isCompact || (isLargeText && widthDp < WIDE_ENOUGH_DP)

    companion object {
        const val COMPACT_WIDTH_DP = 360
        const val SHORT_HEIGHT_DP = 640
        const val LARGE_TEXT_SCALE = 1.3f
        const val WIDE_ENOUGH_DP = 411

        /** Chrome (labels, chips, tabs, toolbar) follows the person's text size up to this. */
        const val CHROME_MAX_FONT_SCALE = 1.3f

        /** The font scale chrome uses: the person's own up to [CHROME_MAX_FONT_SCALE], never forced below what they chose. */
        fun chromeFontScale(fontScale: Float): Float = if (fontScale > CHROME_MAX_FONT_SCALE) CHROME_MAX_FONT_SCALE else fontScale
    }
}
