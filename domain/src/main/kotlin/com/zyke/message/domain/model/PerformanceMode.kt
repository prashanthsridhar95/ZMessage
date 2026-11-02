package com.zyke.message.domain.model

/**
 * How much motion and visual polish the app spends on a phone. [AUTOMATIC] decides from the phone itself (see
 * [LowEndRules] and [LiteMode.resolve]); the other two are the person's own choice.
 */
enum class PerformanceMode(val label: String, val description: String) {
    AUTOMATIC("Automatic", "Full on most phones, lite on phones with little memory"),
    FULL("Full", "All animations and effects, whatever the phone"),
    LITE("Lite", "No looping animations or effects; short fades. Easier on a slow phone and its battery");

    companion object {
        /** The mode stored under [name]; anything missing or unknown (an older or newer build) is [AUTOMATIC]. */
        fun fromStored(name: String?): PerformanceMode = entries.firstOrNull { it.name == name } ?: AUTOMATIC
    }
}

/** Decides whether the app runs in lite mode. Pure, so it is tested without a phone. */
object LiteMode {
    /** Lite when asked for, never when full is asked for, and with [PerformanceMode.AUTOMATIC] when the phone was found to be low-end. */
    fun resolve(mode: PerformanceMode, detectedLowEnd: Boolean): Boolean = when (mode) {
        PerformanceMode.LITE -> true
        PerformanceMode.FULL -> false
        PerformanceMode.AUTOMATIC -> detectedLowEnd
    }
}

/** What counts as a low-end phone. The numbers come from the system (ActivityManager); this only compares them. */
object LowEndRules {
    const val LOW_RAM_BYTES = 3L * 1024 * 1024 * 1024
    const val LOW_MEMORY_CLASS_MB = 192

    /**
     * Low-end when Android itself says so ([isLowRamDevice]), or the phone has about 3 GB of memory or less
     * ([totalMemBytes] > 0), or each app is allowed little heap ([memoryClassMb] > 0). Unknown values (0 or less) never
     * count against the phone.
     */
    fun isLowEnd(isLowRamDevice: Boolean, totalMemBytes: Long, memoryClassMb: Int): Boolean =
        isLowRamDevice ||
            (totalMemBytes in 1..LOW_RAM_BYTES) ||
            (memoryClassMb in 1..LOW_MEMORY_CLASS_MB)
}
