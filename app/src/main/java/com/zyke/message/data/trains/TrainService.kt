package com.zyke.message.data.trains

import android.content.Context
import com.zyke.message.data.diag.DevLog
import com.zyke.message.data.diag.LiveReplyMemo
import com.zyke.message.data.markets.TrainFetcher
import com.zyke.message.domain.demo.DemoTrains
import com.zyke.message.domain.diag.LiveCheck
import com.zyke.message.domain.diag.LiveCheckReport
import com.zyke.message.domain.diag.LiveDiagnosis
import com.zyke.message.domain.diag.LiveFailure
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.Stations
import com.zyke.message.domain.train.TrainLive
import com.zyke.message.domain.train.TrainLookup
import com.zyke.message.domain.train.TrainStatusParser
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Two switches for trains, both about the internet and so separate from the flight ones: live train status is off until the person
 * turns it on; the notification while they travel is on unless they turn it off. Only these flags are stored (and so backed up). Whether
 * the lock screen shows the details is the one flight switch (a trip is not an amount): the train notifications read it too.
 */
class TrainSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("train_status", Context.MODE_PRIVATE)
    private val live = MutableStateFlow(prefs.getBoolean(LIVE, false))
    private val tracking = MutableStateFlow(prefs.getBoolean(TRACK, true))
    val liveEnabled: StateFlow<Boolean> = live
    val trackingEnabled: StateFlow<Boolean> = tracking

    fun setLiveEnabled(on: Boolean) {
        live.value = on
        prefs.edit().putBoolean(LIVE, on).apply()
    }

    fun setTrackingEnabled(on: Boolean) {
        tracking.value = on
        prefs.edit().putBoolean(TRACK, on).apply()
    }

    private companion object {
        const val LIVE = "live_enabled"
        const val TRACK = "track_in_transit"
    }
}

/** What the card and the sheet know about live data, by journey key. Held in memory only. */
data class TrainLiveState(
    val statuses: Map<String, TrainLive> = emptyMap(),
    /** The last try for this journey could not reach or read the service; the status in [statuses], if any, is the last known. */
    val failed: Set<String> = emptySet(),
    /** The service answered but has no running of that train for that start day (yet). */
    val notFound: Set<String> = emptySet(),
    /** Why the last try failed, for the keys in [failed] (blocked, unreadable, no network...): the card words it. */
    val reasons: Map<String, LiveFailure> = emptyMap(),
    val loading: Boolean = false
)

/**
 * Keeps today's live train status in memory. It asks only when told to: the app coming to the front, the open sheet's 30-second timer
 * (both in the UI, both stopped when the screen is not showing) and the tracking service (`TrainTrackingService`, which runs only while
 * a journey is under way or about to start, and asks every 30 s through this same class and its one fetcher). A reply is reused for
 * [FRESH_MS], so those never double up. In the demo nothing is asked of the network: [DemoTrains] makes up a status from the clock.
 */
class TrainService(private val settings: TrainSettings, private val demo: Boolean) {
    private val fetcher = TrainFetcher()
    private val lock = Mutex()
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val boardingDays = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, LiveFailure>()
    private val _live = MutableStateFlow(TrainLiveState())
    val live: StateFlow<TrainLiveState> = _live

    val liveEnabled: StateFlow<Boolean> get() = settings.liveEnabled
    val trackingEnabled: StateFlow<Boolean> get() = settings.trackingEnabled

    fun setLiveEnabled(on: Boolean) = settings.setLiveEnabled(on)
    fun setTrackingEnabled(on: Boolean) = settings.setTrackingEnabled(on)

    /** When the last try for [key] was made (epoch milliseconds), or 0. The tracking service counts a failure once per try with this. */
    fun lastAttemptAt(key: String): Long = attempts[key] ?: 0L

    /** Whether status is being fetched (or made up, in the demo). */
    fun isOn(): Boolean = demo || settings.liveEnabled.value

    /**
     * Asks the live source once about a journey the person is adding by hand, to confirm its name and schedule. Null when live status
     * is off, in the demo (nothing is asked of the network there), or the service could not be reached; otherwise what it said. It does
     * not touch the statuses the card shows.
     */
    suspend fun lookup(train: BookedTrain): TrainLookup? {
        if (demo || !settings.liveEnabled.value) return null
        return withContext(Dispatchers.IO) { ask(train) }
    }

    /**
     * Developer tools only (Settings, Flights and trains, with developer options on): one real request for [number] (a run that started
     * today) with everything about the answer, kept as the one latest raw reply in memory for sharing. Null in the demo and for a number
     * that is not five digits. It does not touch the statuses the card shows. The request carries only the train number, as ever.
     */
    suspend fun diagnose(number: String): LiveCheckReport? {
        if (demo) return null
        return withContext(Dispatchers.IO) {
            val reply = fetcher.diagnose(number, 0) ?: return@withContext null
            val digits = number.filter { it.isDigit() }.take(5)
            val body = reply.body
            val missing = if (body != null && reply.statusCode in 200..299) TrainStatusParser.firstMissingField(body) else null
            var diagnosis = LiveCheck.classify(reply, missing)
            var parsed: String? = null
            if (diagnosis.ok && body != null) {
                val today = LocalDate.now(Stations.ZONE)
                val sample = BookedTrain(digits, null, today, null, null, null, null)
                when (TrainStatusParser.parse(body, sample, today, System.currentTimeMillis())) {
                    is TrainLookup.Found -> parsed = "found"
                    is TrainLookup.NotFound -> parsed = "no running of this train today"
                    is TrainLookup.Unreadable -> {
                        parsed = "unreadable"
                        diagnosis = LiveDiagnosis(LiveFailure.SHAPE_MISMATCH, "a field the parser needs (see the raw reply)")
                    }
                }
            }
            val report = LiveCheckReport("Train $digits", reply, diagnosis, parsed)
            LiveReplyMemo.put(report, body.orEmpty())
            report
        }
    }

    /** One run's reply: the run starting on the journey day less what is known of the boarding day; asked again once if the reply says otherwise. */
    private fun ask(train: BookedTrain): TrainLookup? {
        var day = boardingDays[train.key] ?: train.boardingDay
        repeat(2) { attempt ->
            val start = train.date.minusDays((day - 1).coerceAtLeast(0).toLong())
            val today = LocalDate.now(Stations.ZONE)
            // A run that has not started yet has no running status to ask for.
            if (start.isAfter(today)) return TrainLookup.NotFound
            val reply = fetcher.diagnose(train.number, ChronoUnit.DAYS.between(start, today).toInt())
            if (reply == null) { failures[train.key] = LiveFailure.OTHER; return null }
            val body = reply.body
            val problem = LiveCheck.classify(reply).reason
            if (problem != LiveFailure.NONE || body == null) { failures[train.key] = problem; return null }
            val result = TrainStatusParser.parse(body, train, start, System.currentTimeMillis())
            if (result is TrainLookup.Unreadable) failures[train.key] = LiveFailure.SHAPE_MISMATCH else failures.remove(train.key)
            val learned = (result as? TrainLookup.Found)?.live?.boardingDay
            if (learned != null && learned != day && attempt == 0) {
                boardingDays[train.key] = learned
                day = learned
                return@repeat
            }
            return result
        }
        return null
    }

    /**
     * Asks once for each of [trains] (at most [MAX_TRAINS]) whose last answer is older than [FRESH_MS]. Safe to call often and from
     * several places: calls wait for each other, and the second finds the first's answer fresh.
     */
    suspend fun refresh(trains: List<BookedTrain>, manual: Boolean = false) {
        if (!isOn() || trains.isEmpty()) return
        lock.withLock {
            val minGap = if (manual) MANUAL_MIN_MS else FRESH_MS
            val due = trains.take(MAX_TRAINS).filter { System.currentTimeMillis() - (attempts[it.key] ?: 0L) >= minGap }
            if (due.isEmpty()) return
            _live.update { it.copy(loading = true) }
            due.forEach { train ->
                val now = System.currentTimeMillis()
                attempts[train.key] = now
                if (demo) {
                    val status = DemoTrains.live(train, now)
                    _live.update { it.copy(statuses = it.statuses + (train.key to status), failed = it.failed - train.key, notFound = it.notFound - train.key) }
                    return@forEach
                }
                val result = withContext(Dispatchers.IO) { ask(train) }
                DevLog.d("train") { "refresh: ${result?.javaClass?.simpleName ?: "no reply"}" }
                val why = failures[train.key]
                _live.update { current ->
                    when (result) {
                        is TrainLookup.Found -> current.copy(statuses = current.statuses + (train.key to result.live), failed = current.failed - train.key, notFound = current.notFound - train.key, reasons = current.reasons - train.key)
                        is TrainLookup.NotFound -> current.copy(failed = current.failed - train.key, notFound = current.notFound + train.key, reasons = current.reasons - train.key)
                        else -> current.copy(failed = current.failed + train.key, reasons = if (why != null) current.reasons + (train.key to why) else current.reasons - train.key)
                    }
                }
            }
            _live.update { it.copy(loading = false) }
        }
    }

    private companion object {
        const val FRESH_MS = 25_000L
        const val MANUAL_MIN_MS = 3_000L
        const val MAX_TRAINS = 3
    }
}
