package com.zyke.message.data.flights

import android.content.Context
import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightPlanCodec

/**
 * What the alarm, the boot receiver and the tracking service know about the flights found in the person's texts when the app
 * is not open: the flights themselves (without the PNR; see [FlightPlanCodec]), which flights the person pressed "Stop tracking"
 * on, and which have had their reminder. Bookkeeping only: the file is left out of backups and rewritten whenever Home is shown.
 */
class FlightPlanStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("flight_plan", Context.MODE_PRIVATE)

    fun fromTexts(): List<BookedFlight> = runCatching { FlightPlanCodec.decode(prefs.getString(FLIGHTS, "").orEmpty()) }.getOrDefault(emptyList())

    fun saveFromTexts(flights: List<BookedFlight>) {
        val text = FlightPlanCodec.encode(flights)
        if (text != prefs.getString(FLIGHTS, "")) prefs.edit().putString(FLIGHTS, text).apply()
    }

    /** Keys of flights the person stopped tracking. The day is in the key, so a stop never hides another day's flight. */
    fun stopped(): Set<String> = prefs.getStringSet(STOPPED, emptySet()).orEmpty().toSet()

    fun stop(key: String) {
        prefs.edit().putStringSet(STOPPED, (stopped() + key).toList().takeLast(KEEP).toSet()).apply()
    }

    /** Putting tracking back for a flight (the person added it again, or turned the switches on again). */
    fun resume(key: String) {
        if (key in stopped()) prefs.edit().putStringSet(STOPPED, stopped() - key).apply()
    }

    /** True the first time it is asked for a flight, so a reminder is posted once. */
    fun markReminded(key: String): Boolean {
        val done = prefs.getStringSet(REMINDED, emptySet()).orEmpty()
        if (key in done) return false
        prefs.edit().putStringSet(REMINDED, (done + key).toList().takeLast(KEEP).toSet()).apply()
        return true
    }

    private companion object {
        const val FLIGHTS = "flights"
        const val STOPPED = "stopped"
        const val REMINDED = "reminded"
        const val KEEP = 40
    }
}
