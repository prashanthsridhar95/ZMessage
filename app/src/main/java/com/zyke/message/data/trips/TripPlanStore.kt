package com.zyke.message.data.trips

import android.content.Context

/**
 * What the alarm, the boot receiver and the tracking service know about the trips (flights or train journeys) found in the person's
 * texts when the app is not open: the trips themselves (without the PNR; the kind's codec), which ones the person pressed "Stop
 * tracking" on, and which have had their reminder. Bookkeeping only: the file ([fileName]) is left out of backups and rewritten
 * whenever Home is shown. [FlightPlanStore] and [TrainPlanStore] are this with their own file, list key and codec.
 */
open class TripPlanStore<B>(
    context: Context,
    fileName: String,
    private val listKey: String,
    private val encode: (List<B>) -> String,
    private val decode: (String) -> List<B>
) {
    private val prefs = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun fromTexts(): List<B> = runCatching { decode(prefs.getString(listKey, "").orEmpty()) }.getOrDefault(emptyList())

    fun saveFromTexts(trips: List<B>) {
        val text = encode(trips)
        if (text != prefs.getString(listKey, "")) prefs.edit().putString(listKey, text).apply()
    }

    /** Keys of trips the person stopped tracking. The day is in the key, so a stop never hides another day's trip. */
    fun stopped(): Set<String> = prefs.getStringSet(STOPPED, emptySet()).orEmpty().toSet()

    fun stop(key: String) {
        prefs.edit().putStringSet(STOPPED, (stopped() + key).toList().takeLast(KEEP).toSet()).apply()
    }

    /** Putting tracking back for a trip (the person added it again, or turned the switches on again). */
    fun resume(key: String) {
        if (key in stopped()) prefs.edit().putStringSet(STOPPED, stopped() - key).apply()
    }

    /** True the first time it is asked for a trip, so a reminder is posted once. */
    fun markReminded(key: String): Boolean {
        val done = prefs.getStringSet(REMINDED, emptySet()).orEmpty()
        if (key in done) return false
        prefs.edit().putStringSet(REMINDED, (done + key).toList().takeLast(KEEP).toSet()).apply()
        return true
    }

    private companion object {
        const val STOPPED = "stopped"
        const val REMINDED = "reminded"
        const val KEEP = 40
    }
}
