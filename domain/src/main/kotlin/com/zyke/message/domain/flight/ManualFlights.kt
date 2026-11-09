package com.zyke.message.domain.flight

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A flight the person added by hand: airline, number and day, an optional PNR note for themselves, and (when a look-up
 * confirmed it) the route and the scheduled times. Times are on each airport's own clock, like a [BookedFlight]'s.
 * [pnrNote] is shown to the person and never sent anywhere.
 */
data class ManualFlight(
    val id: String,
    val airlineCode: String,
    /** Digits only, as printed after the airline code. */
    val number: String,
    val date: LocalDate,
    val pnrNote: String? = null,
    val originCode: String? = null,
    val destinationCode: String? = null,
    val departure: LocalTime? = null,
    val arrival: LocalTime? = null,
    /** The look-up found this flight and the route and times above come from it. */
    val confirmed: Boolean = false,
    /** Epoch milliseconds when it was added. */
    val addedAt: Long = 0L
) {
    val airline: Airline get() = Airlines.byCode(airlineCode) ?: Airline(airlineCode.uppercase(), airlineCode.uppercase())

    /** The same key a [BookedFlight] of this flight has, so a flight found in a text and typed in by hand is one flight. */
    val key: String get() = "${airline.code}$number|$date"

    /** "6E 2345". */
    val code: String get() = "${airline.code} $number"

    fun toBooked(): BookedFlight = BookedFlight(
        airline = airline, number = number, date = date,
        origin = originCode?.let { Place(it.uppercase(), Airports.city(it)) },
        destination = destinationCode?.let { Place(it.uppercase(), Airports.city(it)) },
        departure = departure, arrival = arrival, pnr = pnrNote, terminal = null, gate = null,
        status = FlightStatusKind.SCHEDULED, delayMinutes = null, newDeparture = null, lastMessageAt = addedAt
    )
}

/** What checking the form's fields came to. */
sealed interface ManualFlightCheck {
    data class Ok(val airline: Airline, val number: String, val date: LocalDate, val pnrNote: String?) : ManualFlightCheck
    /** [field] is "airline", "number", "date" or "pnr"; [message] is the words to show under it. */
    data class Problem(val field: String, val message: String) : ManualFlightCheck
}

object ManualFlightRules {
    /** A day earlier than today is allowed (a flight that left an hour ago, over midnight); a year ahead is the limit. */
    const val DAYS_BEFORE = 1L
    const val DAYS_AHEAD = 366L
    const val MAX_NUMBER_DIGITS = 4
    const val MAX_PNR = 10

    /** The digits of a typed flight number, without the airline's letters if they were typed too ("6E 2345" with 6E chosen is "2345"). */
    fun numberDigits(text: String, airlineCode: String?): String {
        var t = text.uppercase().filter { !it.isWhitespace() && it != '-' }
        if (airlineCode != null && t.startsWith(airlineCode.uppercase()) && t.length > airlineCode.length) t = t.removePrefix(airlineCode.uppercase())
        return t
    }

    /** Words under the number field while it is wrong, or null when it is fine (or empty: nothing to complain about yet). */
    fun numberProblem(text: String, airlineCode: String?): String? {
        val t = numberDigits(text, airlineCode)
        return when {
            t.isEmpty() -> null
            !t.all { it in '0'..'9' } -> "Digits only, like 2345"
            t.length > MAX_NUMBER_DIGITS -> "A flight number has up to 4 digits"
            t.toInt() == 0 -> "Check the flight number"
            else -> null
        }
    }

    fun check(airline: Airline?, numberText: String, date: LocalDate?, pnrText: String, today: LocalDate): ManualFlightCheck {
        if (airline == null) return ManualFlightCheck.Problem("airline", "Choose the airline")
        val digits = numberDigits(numberText, airline.code)
        if (digits.isEmpty()) return ManualFlightCheck.Problem("number", "Enter the flight number")
        numberProblem(numberText, airline.code)?.let { return ManualFlightCheck.Problem("number", it) }
        if (date == null) return ManualFlightCheck.Problem("date", "Choose the day")
        if (date.isBefore(today.minusDays(DAYS_BEFORE))) return ManualFlightCheck.Problem("date", "That day has passed")
        if (date.isAfter(today.plusDays(DAYS_AHEAD))) return ManualFlightCheck.Problem("date", "Choose a day within a year")
        val pnr = pnrText.uppercase().filter { !it.isWhitespace() }
        if (pnr.isNotEmpty() && (!pnr.all { it in '0'..'9' || it in 'A'..'Z' } || pnr.length > MAX_PNR)) {
            return ManualFlightCheck.Problem("pnr", "Letters and numbers only, up to $MAX_PNR")
        }
        // Digits as a number, so "0123" and "123" are the same flight; the parser of texts reads digits the same way.
        return ManualFlightCheck.Ok(airline, digits.toInt().toString(), date, pnr.ifEmpty { null })
    }

    /** The airlines to choose from whose name or code contains what was typed, the typed code's own airline first. */
    fun airlines(query: String): List<Airline> {
        val q = query.trim().lowercase()
        val all = Airlines.list()
        if (q.isEmpty()) return all
        return all.filter { it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
            .sortedBy { if (it.code.lowercase() == q) 0 else if (it.name.lowercase().startsWith(q)) 1 else 2 }
    }
}

object ManualFlights {
    /** A manual flight goes a day after it should have landed. */
    const val KEEP_AFTER_LANDING_HOURS = 24L
    private const val UNKNOWN_FLIGHT_HOURS = 6L

    /**
     * Texts about a flight and a flight typed in by hand are one flight when airline, number and day are the same: the texts'
     * version is kept (it carries the delays and gates) and fills only what it lacks from the hand-made one.
     */
    fun merge(fromTexts: List<BookedFlight>, manual: List<ManualFlight>): List<BookedFlight> {
        val byKey = LinkedHashMap<String, BookedFlight>()
        fromTexts.forEach { byKey.putIfAbsent(it.key, it) }
        for (m in manual) {
            val own = m.toBooked()
            val text = byKey[own.key]
            byKey[own.key] = if (text == null) own else text.copy(
                origin = text.origin ?: own.origin,
                destination = text.destination ?: own.destination,
                departure = text.departure ?: own.departure,
                arrival = text.arrival ?: own.arrival,
                pnr = text.pnr ?: own.pnr
            )
        }
        return byKey.values.toList()
    }

    /** True when [m] has been over for a day: it should have landed [KEEP_AFTER_LANDING_HOURS] ago (or, with no times known, its day has ended by that much). */
    fun isExpired(m: ManualFlight, now: Instant, zone: ZoneId): Boolean {
        val booked = m.toBooked()
        val landing = FlightTracker.scheduledArrival(booked, zone)
            ?: FlightTracker.scheduledDeparture(booked, zone)?.plus(Duration.ofHours(UNKNOWN_FLIGHT_HOURS))
            ?: booked.date.plusDays(1).atStartOfDay(booked.departureZone(zone)).toInstant()
        return now.isAfter(landing.plus(Duration.ofHours(KEEP_AFTER_LANDING_HOURS)))
    }

    /**
     * [m] with the route and times a look-up found. The times are put on each airport's own clock. Only what the source gave is
     * filled in; the flight is marked confirmed. A source that did not know the flight leaves it unchanged.
     */
    fun confirmed(m: ManualFlight, status: LiveStatus, zone: ZoneId): ManualFlight {
        val originZone = Airports.zone(status.originCode) ?: zone
        val destZone = Airports.zone(status.destinationCode) ?: originZone
        return m.copy(
            originCode = status.originCode?.uppercase() ?: m.originCode,
            destinationCode = status.destinationCode?.uppercase() ?: m.destinationCode,
            departure = status.scheduledDeparture?.atZone(originZone)?.toLocalTime() ?: m.departure,
            arrival = status.scheduledArrival?.atZone(destZone)?.toLocalTime() ?: m.arrival,
            confirmed = true
        )
    }
}

/** Where the hand-made flights are kept. */
interface ManualFlightRepository {
    val flights: StateFlow<List<ManualFlight>>

    /** Adds [flight], or replaces the one with the same id (an edit) or the same airline, number and day. */
    fun save(flight: ManualFlight)

    /** Removes the flight with [id] and returns it (for Undo), or null if there is none. */
    fun remove(id: String): ManualFlight?

    /** Drops the flights that have been over for a day. Returns how many went. */
    fun prune(now: Instant, zone: ZoneId): Int
}

/** Keeps nothing (tests, and anywhere the app has no place to keep flights). */
object NoManualFlights : ManualFlightRepository {
    override val flights: StateFlow<List<ManualFlight>> = MutableStateFlow(emptyList<ManualFlight>()).asStateFlow()
    override fun save(flight: ManualFlight) = Unit
    override fun remove(id: String): ManualFlight? = null
    override fun prune(now: Instant, zone: ZoneId): Int = 0
}

/** The working part of the repository, free of Android so it can be tested: every change is applied, published and saved under one lock. */
class ManualFlightStore(initial: List<ManualFlight> = emptyList(), private val persist: (List<ManualFlight>) -> Unit = {}) : ManualFlightRepository {
    private val lock = Any()
    private val state = MutableStateFlow(initial.sortedWith(ORDER))
    override val flights: StateFlow<List<ManualFlight>> = state.asStateFlow()

    override fun save(flight: ManualFlight) = change { list ->
        list.filter { it.id != flight.id && it.key != flight.key } + flight
    }

    override fun remove(id: String): ManualFlight? = synchronized(lock) {
        val found = state.value.firstOrNull { it.id == id } ?: return null
        change { list -> list.filter { it.id != id } }
        found
    }

    override fun prune(now: Instant, zone: ZoneId): Int = synchronized(lock) {
        val before = state.value
        val kept = before.filter { !ManualFlights.isExpired(it, now, zone) }
        if (kept.size != before.size) change { kept }
        before.size - kept.size
    }

    private fun change(apply: (List<ManualFlight>) -> List<ManualFlight>) = synchronized(lock) {
        val after = apply(state.value).sortedWith(ORDER)
        state.value = after
        persist(after)
    }

    private companion object {
        val ORDER = compareBy<ManualFlight>({ it.date }, { it.departure ?: LocalTime.MAX }, { it.addedAt })
    }
}
