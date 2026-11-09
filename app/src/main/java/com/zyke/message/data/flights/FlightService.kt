package com.zyke.message.data.flights

import android.content.Context
import com.zyke.message.data.diag.DevLog
import com.zyke.message.data.markets.FlightFetcher
import com.zyke.message.domain.demo.DemoFlights
import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightLookup
import com.zyke.message.domain.flight.FlightStatusParser
import com.zyke.message.domain.flight.LiveStatus
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The one switch: live flight status is off until the person turns it on. Only this flag is stored (and so backed up). */
class FlightSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("flight_status", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(LIVE, false))
    val liveEnabled: StateFlow<Boolean> = state

    fun setLiveEnabled(on: Boolean) {
        state.value = on
        prefs.edit().putBoolean(LIVE, on).apply()
    }

    private companion object {
        const val LIVE = "live_enabled"
    }
}

/** What the card and the sheet know about live data, by flight key. Held in memory only. */
data class FlightLive(
    val statuses: Map<String, LiveStatus> = emptyMap(),
    /** The last try for this flight could not reach or read the service; the status in [statuses], if any, is the last known. */
    val failed: Set<String> = emptySet(),
    /** The service answered but has no flight of that number on that day (yet). */
    val notFound: Set<String> = emptySet(),
    val loading: Boolean = false
)

/**
 * Keeps today's live flight status in memory. It asks only when the app is told to (the app coming to the front, and the
 * open sheet's 30-second timer, both in the UI and both stopped when the screen is not showing): no background work, no
 * alarms, no service. A reply is reused for [FRESH_MS], so those two never double up; a manual refresh may ask again after
 * [MANUAL_MIN_MS]. In the demo nothing is asked of the network: [DemoFlights] makes up a status from the clock.
 */
class FlightService(private val settings: FlightSettings, private val demo: Boolean) {
    private val fetcher = FlightFetcher()
    private val lock = Mutex()
    private val attempts = HashMap<String, Long>()
    private val _live = MutableStateFlow(FlightLive())
    val live: StateFlow<FlightLive> = _live

    val liveEnabled: StateFlow<Boolean> get() = settings.liveEnabled

    /** Whether status is being fetched (or made up, in the demo). */
    fun isOn(): Boolean = demo || settings.liveEnabled.value

    fun setLiveEnabled(on: Boolean) = settings.setLiveEnabled(on)

    /**
     * Asks once for each of [flights] (at most [MAX_FLIGHTS]) whose last answer is older than [FRESH_MS]. Safe to call often and
     * from several places: calls wait for each other, and the second finds the first's answer fresh.
     */
    suspend fun refresh(flights: List<BookedFlight>, zone: ZoneId, manual: Boolean = false) {
        if (!isOn() || flights.isEmpty()) return
        lock.withLock {
            val minGap = if (manual) MANUAL_MIN_MS else FRESH_MS
            val due = flights.take(MAX_FLIGHTS).filter { System.currentTimeMillis() - (attempts[it.key] ?: 0L) >= minGap }
            if (due.isEmpty()) return
            _live.update { it.copy(loading = true) }
            due.forEach { flight ->
                val now = System.currentTimeMillis()
                attempts[flight.key] = now
                if (demo) {
                    val status = DemoFlights.live(flight, now, zone)
                    _live.update { it.copy(statuses = it.statuses + (flight.key to status), failed = it.failed - flight.key, notFound = it.notFound - flight.key) }
                    return@forEach
                }
                val body = withContext(Dispatchers.IO) { fetcher.fetch(flight.airline, flight.number) }
                val result = body?.let { FlightStatusParser.parse(it, flight, System.currentTimeMillis(), zone) }
                DevLog.d("flight") { "refresh: ${result?.javaClass?.simpleName ?: "no reply"}" }
                _live.update { current ->
                    when (result) {
                        is FlightLookup.Found -> current.copy(statuses = current.statuses + (flight.key to result.status), failed = current.failed - flight.key, notFound = current.notFound - flight.key)
                        is FlightLookup.NotFound -> current.copy(failed = current.failed - flight.key, notFound = current.notFound + flight.key)
                        else -> current.copy(failed = current.failed + flight.key)
                    }
                }
            }
            _live.update { it.copy(loading = false) }
        }
    }

    private companion object {
        /** A reply is reused for this long, so the 30-second sheet timer and a screen start close together ask once. */
        const val FRESH_MS = 25_000L
        const val MANUAL_MIN_MS = 3_000L
        const val MAX_FLIGHTS = 3
    }
}
