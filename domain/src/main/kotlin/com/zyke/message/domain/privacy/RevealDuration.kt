package com.zyke.message.domain.privacy

/**
 * How long hidden amounts stay visible once the person has asked for them (after the screen-lock check). The person
 * chooses; the default is one minute.
 */
enum class RevealDuration(val id: String, val label: String, val description: String, val millis: Long?, val staysAfterLeaving: Boolean = false) {
    SECONDS_30("30s", "30 seconds", "Hidden again after half a minute", 30_000L),
    MINUTE_1("1m", "1 minute", "Hidden again after a minute", 60_000L),
    MINUTES_5("5m", "5 minutes", "Hidden again after five minutes", 300_000L),
    UNTIL_LEAVING("leave", "Until I leave the app", "Stay shown while the app is open; hidden when I leave it", null),
    UNTIL_HIDDEN("manual", "Until I hide them", "Stay shown, even after leaving the app, until I hide them myself", null, staysAfterLeaving = true);

    /** True when amounts go back to hidden by themselves while the app stays open. */
    val isTimed: Boolean get() = millis != null

    companion object {
        val DEFAULT = MINUTE_1

        /** What was stored; anything unknown or missing is the default. */
        fun fromStored(id: String?): RevealDuration = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
