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
import com.zyke.message.domain.train.IndianRailApiParser
import com.zyke.message.domain.train.TrainApi
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The settings for trains. Live train status needs the person's own train data key (from the service, pasted in Settings) and is off
 * until they switch it on; the notification while they travel is on unless they turn it off. Only the two flags are in `train_status`
 * (and so backed up). The key and the day's request count are in two device-only files that no backup takes (see [TrainKeyStore]).
 * Whether the lock screen shows the details is the one flight switch (a trip is not an amount): the train notifications read it too.
 */
class TrainSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("train_status", Context.MODE_PRIVATE)
    private val keys = TrainKeyStore(context)
    private val live = MutableStateFlow(prefs.getBoolean(LIVE, false))
    private val effective = MutableStateFlow(live.value && keys.hasKey.value)
    private val tracking = MutableStateFlow(prefs.getBoolean(TRACK, true))

    /** Whether live train status is really on: the switch is on and a key is saved. Everything that asks reads this one. */
    val liveEnabled: StateFlow<Boolean> = effective

    /** The switch as the person left it, whether or not a key is saved (a restored backup can have it on with no key). */
    val liveSwitch: StateFlow<Boolean> = live
    val trackingEnabled: StateFlow<Boolean> = tracking
    val hasKey: StateFlow<Boolean> = keys.hasKey
    val requestsToday: StateFlow<Int> = keys.requestsToday

    /** Bumped each time the key is saved or cleared, so the service can start again after "key rejected". */
    @Volatile
    var keyEpoch: Int = 0
        private set

    /** Turning it on does nothing without a key. */
    fun setLiveEnabled(on: Boolean) {
        if (on && !keys.hasKey.value) return
        live.value = on
        effective.value = on && keys.hasKey.value
        prefs.edit().putBoolean(LIVE, on).apply()
    }

    fun setTrackingEnabled(on: Boolean) {
        tracking.value = on
        prefs.edit().putBoolean(TRACK, on).apply()
    }

    /** Saves the key if it can be one (see [TrainApi.keyProblem]); false if not. */
    fun saveKey(raw: String): Boolean {
        if (!keys.save(raw)) return false
        keyEpoch += 1
        effective.value = live.value
        return true
    }

    /** Forgets the key and switches live status off: there is nothing to ask with. */
    fun clearKey() {
        keys.clear()
        keyEpoch += 1
        live.value = false
        effective.value = false
        prefs.edit().putBoolean(LIVE, false).apply()
    }

    fun maskedKey(): String = keys.masked()
    internal fun key(): String? = keys.get()
    internal fun recordRequest() = keys.recordRequest()
    fun syncDay() = keys.syncDay()

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
    /** Why the last try failed, for the keys in [failed] (key rejected, limit reached, unreadable, no network...): the card words it. */
    val reasons: Map<String, LiveFailure> = emptyMap(),
    /** Journeys the service has stopped asking about: three answers in a row said the key was rejected or the limit was reached. */
    val paused: Set<String> = emptySet(),
    val loading: Boolean = false
)

/**
 * Keeps today's live train status in memory. It asks only when told to: the app coming to the front, the open sheet's 30-second timer
 * (both in the UI, both stopped when the screen is not showing) and the tracking service (`TrainTrackingService`, which runs only while
 * a journey is under way or about to start, and asks every 30 s through this same class and its one fetcher). A reply is reused for
 * [FRESH_MS], so those never double up. Every request counts against the person's plan with the provider, so a journey whose last
 * [PAUSE_AFTER] answers in a row said "key rejected" or "limit reached" is not asked about again until the key is saved again or the
 * next day. In the demo nothing is asked of the network: [DemoTrains] makes up a status from the clock.
 */
class TrainService(private val settings: TrainSettings, private val demo: Boolean) {
    private val fetcher = TrainFetcher()
    private val lock = Mutex()
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val boardingDays = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, LiveFailure>()
    /** Answers in a row that said the key was rejected or the limit was reached, by journey key. */
    private val refusals = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** The day (Indian time) a journey was paused on. */
    private val pausedOn = java.util.concurrent.ConcurrentHashMap<String, LocalDate>()
    @Volatile
    private var seenKeyEpoch = settings.keyEpoch
    private val _live = MutableStateFlow(TrainLiveState())
    val live: StateFlow<TrainLiveState> = _live

    /** Live train status is on and a key is saved. */
    val liveEnabled: StateFlow<Boolean> get() = settings.liveEnabled
    /** The switch as the person left it. */
    val liveSwitch: StateFlow<Boolean> get() = settings.liveSwitch
    val trackingEnabled: StateFlow<Boolean> get() = settings.trackingEnabled
    val hasKey: StateFlow<Boolean> get() = settings.hasKey
    /** How many requests went out today (Indian time); never includes the key. */
    val requestsToday: StateFlow<Int> get() = settings.requestsToday

    fun setLiveEnabled(on: Boolean) = settings.setLiveEnabled(on)
    fun setTrackingEnabled(on: Boolean) = settings.setTrackingEnabled(on)
    fun saveKey(raw: String): Boolean = settings.saveKey(raw).also { if (it) resumePaused() }
    fun clearKey() = settings.clearKey().also { resumePaused() }
    fun maskedKey(): String = settings.maskedKey()
    fun syncDay() = settings.syncDay()

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
     * today) with everything about the answer, kept as the one latest raw reply in memory for sharing, with the key taken out. Null in
     * the demo, with no key saved, and for a number that is not five digits. It does not touch the statuses the card shows. The request
     * carries the train number, the date and the key, as ever.
     */
    suspend fun diagnose(number: String): LiveCheckReport? {
        if (demo) return null
        val key = settings.key() ?: return null
        return withContext(Dispatchers.IO) {
            val today = LocalDate.now(Stations.ZONE)
            val reply = fetcher.diagnose(number, today, key, onSend = settings::recordRequest) ?: return@withContext null
            val digits = number.filter { it.isDigit() }.take(5)
            val body = reply.body
            val ok = body != null && reply.statusCode in 200..299
            val missing = if (ok) IndianRailApiParser.firstMissingField(body.orEmpty()) else null
            var diagnosis = LiveCheck.classify(reply, missing, keyed = true)
            var parsed: String? = null
            if (diagnosis.ok && body != null) {
                val sample = BookedTrain(digits, null, today, null, null, null, null)
                when (val result = IndianRailApiParser.parse(body, sample, today, System.currentTimeMillis())) {
                    is TrainLookup.Found -> parsed = "found"
                    is TrainLookup.NotFound -> parsed = "no running of this train today"
                    is TrainLookup.Refused -> {
                        parsed = "refused"
                        diagnosis = LiveDiagnosis(result.failure)
                    }
                    is TrainLookup.Unreadable -> {
                        parsed = "unreadable"
                        diagnosis = LiveDiagnosis(LiveFailure.SHAPE_MISMATCH, "a field the parser needs (see the raw reply)")
                    }
                }
            }
            val note = if (ok) IndianRailApiParser.providerNote(body.orEmpty())?.let { TrainApi.redact(it, key) } else null
            val report = LiveCheckReport("Train $digits", reply, diagnosis, parsed, note, TrainApi.redactedRequest(digits, today))
            LiveReplyMemo.put(report, TrainApi.redact(body.orEmpty(), key))
            report
        }
    }

    /** One run's reply: the run starting on the journey day less what is known of the boarding day; asked again once if the reply says otherwise. */
    private fun ask(train: BookedTrain): TrainLookup? {
        val key = settings.key()
        if (key == null) { failures[train.key] = LiveFailure.KEY_REJECTED; return null }
        var day = boardingDays[train.key] ?: train.boardingDay
        repeat(2) { attempt ->
            val start = train.date.minusDays((day - 1).coerceAtLeast(0).toLong())
            val today = LocalDate.now(Stations.ZONE)
            // A run that has not started yet, or ended long ago, has no running status worth a request.
            if (start.isAfter(today) || start.isBefore(today.minusDays(MAX_DAYS_BACK))) return TrainLookup.NotFound
            val reply = fetcher.diagnose(train.number, start, key, onSend = settings::recordRequest)
            if (reply == null) { failures[train.key] = LiveFailure.OTHER; return null }
            val body = reply.body
            val problem = LiveCheck.classify(reply, keyed = true).reason
            if (problem != LiveFailure.NONE || body == null) { failures[train.key] = problem; return null }
            val result = IndianRailApiParser.parse(body, train, start, System.currentTimeMillis())
            when (result) {
                is TrainLookup.Unreadable -> failures[train.key] = LiveFailure.SHAPE_MISMATCH
                is TrainLookup.Refused -> failures[train.key] = result.failure
                else -> failures.remove(train.key)
            }
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

    /** Counts answers in a row that refused the key or the limit, and stops asking about a journey after [PAUSE_AFTER] of them. */
    private fun noteOutcome(trainKey: String, failure: LiveFailure?) {
        if (failure == LiveFailure.KEY_REJECTED || failure == LiveFailure.LIMIT_REACHED) {
            val count = (refusals[trainKey] ?: 0) + 1
            refusals[trainKey] = count
            if (count >= PAUSE_AFTER) pausedOn[trainKey] = LocalDate.now(Stations.ZONE)
        } else {
            refusals.remove(trainKey)
        }
    }

    /** Whether [trainKey] was paused today. A pause from an earlier day, or from before the key was saved again, is over. */
    private fun isPaused(trainKey: String): Boolean {
        val epoch = settings.keyEpoch
        if (epoch != seenKeyEpoch) resumePaused()
        val since = pausedOn[trainKey] ?: return false
        if (since == LocalDate.now(Stations.ZONE)) return true
        pausedOn.remove(trainKey)
        refusals.remove(trainKey)
        return false
    }

    private fun resumePaused() {
        seenKeyEpoch = settings.keyEpoch
        pausedOn.clear()
        refusals.clear()
        _live.update { it.copy(paused = emptySet()) }
    }

    /**
     * Asks once for each of [trains] (at most [MAX_TRAINS]) whose last answer is older than [FRESH_MS]. Safe to call often and from
     * several places: calls wait for each other, and the second finds the first's answer fresh. A journey that has been paused (see the
     * class note) is skipped.
     */
    suspend fun refresh(trains: List<BookedTrain>, manual: Boolean = false) {
        if (!isOn() || trains.isEmpty()) return
        lock.withLock {
            val minGap = if (manual) MANUAL_MIN_MS else FRESH_MS
            val due = trains.take(MAX_TRAINS).filter { System.currentTimeMillis() - (attempts[it.key] ?: 0L) >= minGap && (demo || !isPaused(it.key)) }
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
                noteOutcome(train.key, if (result is TrainLookup.Found || result is TrainLookup.NotFound) null else why)
                val paused = pausedOn.containsKey(train.key)
                _live.update { current ->
                    val pausedNow = if (paused) current.paused + train.key else current.paused - train.key
                    when (result) {
                        is TrainLookup.Found -> current.copy(statuses = current.statuses + (train.key to result.live), failed = current.failed - train.key, notFound = current.notFound - train.key, reasons = current.reasons - train.key, paused = pausedNow)
                        is TrainLookup.NotFound -> current.copy(failed = current.failed - train.key, notFound = current.notFound + train.key, reasons = current.reasons - train.key, paused = pausedNow)
                        else -> current.copy(failed = current.failed + train.key, reasons = if (why != null) current.reasons + (train.key to why) else current.reasons - train.key, paused = pausedNow)
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
        const val PAUSE_AFTER = 3
        const val MAX_DAYS_BACK = 7L
    }
}
