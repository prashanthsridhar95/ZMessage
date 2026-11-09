package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A train journey the person added by hand: train number, day of journey, optionally the station they board at and a PNR note for
 * themselves, and (when a look-up confirmed it) the name and the schedule. [pnrNote] is shown to the person and never sent anywhere.
 */
data class ManualTrain(
    val id: String,
    /** Five digits. */
    val number: String,
    val date: LocalDate,
    val boardingCode: String? = null,
    val boardingName: String? = null,
    val pnrNote: String? = null,
    val name: String? = null,
    val destinationCode: String? = null,
    val destinationName: String? = null,
    val departure: LocalTime? = null,
    val arrival: LocalTime? = null,
    val arrivalDayOffset: Int = 0,
    val boardingDay: Int = 1,
    /** The look-up found this train and the name and schedule above come from it. */
    val confirmed: Boolean = false,
    val addedAt: Long = 0L
) {
    /** The same key a [BookedTrain] of this journey has, so a journey found in a text and typed in by hand is one journey. */
    val key: String get() = "$number|$date"

    val boarding: Place? get() = if (boardingCode == null && boardingName == null) null else Place(boardingCode, boardingName ?: Stations.name(boardingCode) ?: boardingCode.orEmpty())
    val destination: Place? get() = if (destinationCode == null && destinationName == null) null else Place(destinationCode, destinationName ?: Stations.name(destinationCode) ?: destinationCode.orEmpty())

    fun toBooked(): BookedTrain = BookedTrain(
        number = number, name = name, date = date, from = boarding, to = destination, departure = departure, arrival = arrival,
        arrivalDayOffset = arrivalDayOffset, pnr = pnrNote, boardingDay = boardingDay, lastMessageAt = addedAt
    )
}

sealed interface ManualTrainCheck {
    data class Ok(val number: String, val date: LocalDate, val boarding: Place?, val pnrNote: String?) : ManualTrainCheck
    /** [field] is "number", "date", "station" or "pnr"; [message] is the words to show under it. */
    data class Problem(val field: String, val message: String) : ManualTrainCheck
}

object ManualTrainRules {
    const val DAYS_BEFORE = 1L
    const val DAYS_AHEAD = 366L
    const val MAX_PNR = 10

    fun numberDigits(text: String): String = text.filter { !it.isWhitespace() && it != '-' }

    /** Words under the number field while it is wrong, or null when it is fine (or empty: nothing to complain about yet). */
    fun numberProblem(text: String): String? {
        val t = numberDigits(text)
        return when {
            t.isEmpty() -> null
            !t.all { it in '0'..'9' } -> "Digits only, like 12633"
            t.length > 5 -> "A train number has 5 digits"
            t.length < 5 -> null
            else -> null
        }
    }

    /** The station typed: a code (NDLS) or a name (Chennai Egmore). Null for an empty field; [stationProblem] says when it is wrong. */
    fun station(text: String): Place? {
        val t = text.trim()
        if (t.isEmpty()) return null
        Stations.codeForName(t)?.let { return Stations.place(it) }
        val code = t.uppercase()
        if (code.length in 2..5 && code.all { it in 'A'..'Z' }) return Stations.place(code)
        return null
    }

    fun stationProblem(text: String): String? = if (text.isBlank() || station(text) != null) null else "Use the station code (like MAS) or its name"

    fun check(numberText: String, date: LocalDate?, stationText: String, pnrText: String, today: LocalDate): ManualTrainCheck {
        val digits = numberDigits(numberText)
        if (digits.isEmpty()) return ManualTrainCheck.Problem("number", "Enter the train number")
        numberProblem(numberText)?.let { return ManualTrainCheck.Problem("number", it) }
        if (digits.length != 5) return ManualTrainCheck.Problem("number", "A train number has 5 digits")
        if (date == null) return ManualTrainCheck.Problem("date", "Choose the day")
        if (date.isBefore(today.minusDays(DAYS_BEFORE))) return ManualTrainCheck.Problem("date", "That day has passed")
        if (date.isAfter(today.plusDays(DAYS_AHEAD))) return ManualTrainCheck.Problem("date", "Choose a day within a year")
        stationProblem(stationText)?.let { return ManualTrainCheck.Problem("station", it) }
        val pnr = pnrText.filter { !it.isWhitespace() }
        if (pnr.isNotEmpty() && (!pnr.all { it in '0'..'9' } || pnr.length > MAX_PNR)) return ManualTrainCheck.Problem("pnr", "Digits only, up to $MAX_PNR")
        return ManualTrainCheck.Ok(digits, date, station(stationText), pnr.ifEmpty { null })
    }
}

object ManualTrains {
    /** A manual journey goes a day after it should have arrived. */
    const val KEEP_AFTER_ARRIVAL_HOURS = 24L
    private const val UNKNOWN_JOURNEY_HOURS = 48L

    /**
     * Texts about a journey and a journey typed in by hand are one when the train number and day are the same: the texts' version is
     * kept (it carries the chart, platform and delays) and fills only what it lacks from the hand-made one.
     */
    fun merge(fromTexts: List<BookedTrain>, manual: List<ManualTrain>): List<BookedTrain> {
        val byKey = LinkedHashMap<String, BookedTrain>()
        fromTexts.forEach { byKey.putIfAbsent(it.key, it) }
        for (m in manual) {
            val own = m.toBooked()
            val text = byKey[own.key]
            byKey[own.key] = if (text == null) own else text.copy(
                name = text.name ?: own.name, from = text.from ?: own.from, to = text.to ?: own.to, departure = text.departure ?: own.departure,
                arrival = text.arrival ?: own.arrival, arrivalDayOffset = if (text.arrival == null) own.arrivalDayOffset else text.arrivalDayOffset,
                pnr = text.pnr ?: own.pnr, boardingDay = maxOf(text.boardingDay, own.boardingDay)
            )
        }
        return byKey.values.toList()
    }

    /** True when [m] has been over for a day: it should have arrived [KEEP_AFTER_ARRIVAL_HOURS] ago (or, with no times, two days after its day). */
    fun isExpired(m: ManualTrain, now: Instant): Boolean {
        val booked = m.toBooked()
        val end = TrainTracker.scheduledArrival(booked)
            ?: TrainTracker.scheduledDeparture(booked)?.plus(Duration.ofHours(UNKNOWN_JOURNEY_HOURS))
            ?: booked.date.plusDays(2).atStartOfDay(Stations.ZONE).toInstant()
        return now.isAfter(end.plus(Duration.ofHours(KEEP_AFTER_ARRIVAL_HOURS)))
    }

    /**
     * [m] with the name and schedule a look-up found: the train's name, where it starts and ends (the person's stations when they
     * gave one), the scheduled times at them, and which run day the boarding station is on. A source that did not know the train
     * leaves it unchanged.
     */
    fun confirmed(m: ManualTrain, live: TrainLive): ManualTrain {
        val stops = live.stops
        val board = m.boardingCode?.let { c -> stops.firstOrNull { it.code == c } } ?: stops.firstOrNull()
        val dest = m.destinationCode?.let { c -> stops.firstOrNull { it.code == c } } ?: stops.lastOrNull()
        val departs = board?.scheduledDeparture ?: board?.scheduledArrival
        val arrives = dest?.scheduledArrival ?: dest?.scheduledDeparture
        val offset = if (departs != null && arrives != null) {
            (arrives.atZone(Stations.ZONE).toLocalDate().toEpochDay() - departs.atZone(Stations.ZONE).toLocalDate().toEpochDay()).toInt().coerceAtLeast(0)
        } else m.arrivalDayOffset
        return m.copy(
            name = live.trainName?.let { prettyName(it) } ?: m.name,
            boardingCode = m.boardingCode ?: board?.code, boardingName = m.boardingName ?: board?.name,
            destinationCode = m.destinationCode ?: dest?.code, destinationName = m.destinationName ?: dest?.name,
            departure = departs?.atZone(Stations.ZONE)?.toLocalTime() ?: m.departure,
            arrival = arrives?.atZone(Stations.ZONE)?.toLocalTime() ?: m.arrival,
            arrivalDayOffset = offset, boardingDay = board?.day ?: m.boardingDay, confirmed = true
        )
    }

    /** "KANYAKUMARI EXP" as "Kanyakumari Express". */
    fun prettyName(raw: String): String = raw.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
        when (w.lowercase().trimEnd('.')) {
            "exp" -> "Express"; "sf" -> "SF"; "spl" -> "Special"; "psgr" -> "Passenger"
            else -> w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}

interface ManualTrainRepository {
    val trains: StateFlow<List<ManualTrain>>
    fun save(train: ManualTrain)
    fun remove(id: String): ManualTrain?
    /** Drops the journeys that have been over for a day. Returns how many went. */
    fun prune(now: Instant): Int
}

object NoManualTrains : ManualTrainRepository {
    override val trains: StateFlow<List<ManualTrain>> = MutableStateFlow(emptyList<ManualTrain>()).asStateFlow()
    override fun save(train: ManualTrain) = Unit
    override fun remove(id: String): ManualTrain? = null
    override fun prune(now: Instant): Int = 0
}

/** The working part of the repository, free of Android so it can be tested: every change is applied, published and saved under one lock. */
class ManualTrainStore(initial: List<ManualTrain> = emptyList(), private val persist: (List<ManualTrain>) -> Unit = {}) : ManualTrainRepository {
    private val lock = Any()
    private val state = MutableStateFlow(initial.sortedWith(ORDER))
    override val trains: StateFlow<List<ManualTrain>> = state.asStateFlow()

    override fun save(train: ManualTrain) = change { list -> list.filter { it.id != train.id && it.key != train.key } + train }

    override fun remove(id: String): ManualTrain? = synchronized(lock) {
        val found = state.value.firstOrNull { it.id == id } ?: return null
        change { list -> list.filter { it.id != id } }
        found
    }

    override fun prune(now: Instant): Int = synchronized(lock) {
        val before = state.value
        val kept = before.filter { !ManualTrains.isExpired(it, now) }
        if (kept.size != before.size) change { kept }
        before.size - kept.size
    }

    private fun change(apply: (List<ManualTrain>) -> List<ManualTrain>) = synchronized(lock) {
        val after = apply(state.value).sortedWith(ORDER)
        state.value = after
        persist(after)
    }

    private companion object {
        val ORDER = compareBy<ManualTrain>({ it.date }, { it.departure ?: LocalTime.MAX }, { it.addedAt })
    }
}
