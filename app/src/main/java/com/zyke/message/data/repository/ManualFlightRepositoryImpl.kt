package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.flight.ManualFlight
import com.zyke.message.domain.flight.ManualFlightRepository
import com.zyke.message.domain.flight.ManualFlightStore
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flights the person added by hand, in private preferences (`manual_flights`, which goes into the backup of the person's setup).
 * The changes themselves are in [ManualFlightStore] (atomic, tested); this only loads and saves. In Demo mode the preferences
 * are in memory (see DemoContext), so nothing is kept.
 */
class ManualFlightRepositoryImpl private constructor(
    private val prefs: android.content.SharedPreferences,
    private val store: ManualFlightStore
) : ManualFlightRepository by store {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("manual_flights", Context.MODE_PRIVATE))

    private constructor(prefs: android.content.SharedPreferences) : this(prefs, ManualFlightStore(load(prefs)) { save(prefs, it) })

    private companion object {
        const val FLIGHTS = "flights"

        fun save(prefs: android.content.SharedPreferences, flights: List<ManualFlight>) {
            val array = JSONArray()
            flights.forEach { f ->
                array.put(
                    JSONObject().put("id", f.id).put("a", f.airlineCode).put("n", f.number).put("d", f.date.toString())
                        .put("p", f.pnrNote ?: JSONObject.NULL).put("o", f.originCode ?: JSONObject.NULL).put("x", f.destinationCode ?: JSONObject.NULL)
                        .put("dt", f.departure?.toString() ?: JSONObject.NULL).put("at", f.arrival?.toString() ?: JSONObject.NULL)
                        .put("c", f.confirmed).put("t", f.addedAt)
                )
            }
            prefs.edit().putString(FLIGHTS, array.toString()).apply()
        }

        fun load(prefs: android.content.SharedPreferences): List<ManualFlight> = runCatching {
            val array = JSONArray(prefs.getString(FLIGHTS, "[]"))
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    fun text(key: String) = if (o.isNull(key)) null else o.getString(key)
                    ManualFlight(
                        id = o.getString("id"), airlineCode = o.getString("a"), number = o.getString("n"), date = LocalDate.parse(o.getString("d")),
                        pnrNote = text("p"), originCode = text("o"), destinationCode = text("x"),
                        departure = text("dt")?.let { LocalTime.parse(it) }, arrival = text("at")?.let { LocalTime.parse(it) },
                        confirmed = o.optBoolean("c"), addedAt = o.optLong("t")
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
