package com.zyke.message.domain.flight

import com.zyke.message.domain.life.LifeParser
import java.time.LocalDate
import java.time.LocalTime

/** What an airline text is telling: a booking, a reminder, a new time, a gate, boarding, or a cancellation. */
enum class FlightEvent { BOOKED, REMINDER, RESCHEDULED, CHECK_IN, BOARDING, DELAYED, GATE_CHANGE, CANCELLED }

/** An end of a flight: the airport code when the message gave one or a known city, and the city name. */
data class Place(val code: String?, val city: String) {
    /** What to draw big: the code, or the city when no code is known. */
    val label: String get() = code ?: city
}

/**
 * One text about one flight, as written. Everything a message did not say is null. [pnr] is kept only to be shown to the
 * person (and copied by them); it is never sent anywhere.
 */
data class FlightFacts(
    val airline: Airline,
    /** Digits only, as printed after the airline code: "2345". */
    val number: String,
    val date: LocalDate?,
    val origin: Place?,
    val destination: Place?,
    /** The scheduled departure the message states (for a delay message, the original one when it says so). */
    val departure: LocalTime?,
    val arrival: LocalTime?,
    val pnr: String?,
    val terminal: String?,
    val gate: String?,
    val event: FlightEvent,
    val delayMinutes: Int? = null,
    /** A delay or reschedule message's new departure time. */
    val newDeparture: LocalTime? = null
)

/**
 * Reads airline texts (IndiGo 6E, Air India AI, Vistara UK, SpiceJet SG, Akasa QP, Air India Express IX, and others on
 * [Airlines]) with rules only, so it behaves the same on every phone. A text that has no known airline code with a flight
 * number and a flight word beside it is left alone rather than guessed at. Never throws.
 */
object FlightParser {

    private val SPACES = Regex("\\s+")
    private val CODE_ALT = Airlines.codes.joinToString("|") { Regex.escape(it) }
    private val FLIGHT_NUMBER = Regex("(?<![A-Za-z0-9])($CODE_ALT)[ \\-]?(\\d{1,4})(?![0-9A-Za-z])")
    private val NAMED_FLIGHT = Regex("(?i)\\bflight\\s*(?:no\\.?|number|#)?\\s*[:\\-]?\\s*(\\d{3,4})(?![0-9A-Za-z])")
    private val CONTEXT = Regex("(?i)\\bflight\\b|\\bPNR\\b|\\bairlines?\\b|\\bairways\\b|\\bboarding\\b|\\bweb check-?in\\b|\\bsector\\b|\\bitinerary\\b")

    fun parse(body: String, receivedOn: LocalDate): FlightFacts? = try {
        read(body.take(MAX_CHARS).replace(SPACES, " ").trim(), receivedOn)
    } catch (e: Exception) {
        null
    }

    private const val MAX_CHARS = 1_500

    private fun read(text: String, receivedOn: LocalDate): FlightFacts? {
        if (!CONTEXT.containsMatchIn(text)) return null
        val (airline, number, numberRange) = flightNumber(text) ?: return null
        if (PROMO.containsMatchIn(text) && pnr(text) == null) return null
        val event = eventOf(text)
        val (origin, destination) = route(text)
        val (scheduled, arrival, newDeparture) = times(text, event, numberRange)
        return FlightFacts(
            airline = airline, number = number, date = travelDate(text, receivedOn), origin = origin, destination = destination,
            departure = scheduled, arrival = arrival, pnr = pnr(text), terminal = terminal(text), gate = gate(text), event = event,
            delayMinutes = if (event == FlightEvent.DELAYED) delayMinutes(text) else null,
            newDeparture = if (event == FlightEvent.DELAYED || event == FlightEvent.RESCHEDULED) newDeparture else null
        )
    }

    // ---- who and which

    /** Fare offers mention routes and airlines but are no booking: with no PNR they are left alone. */
    private val PROMO = Regex("(?i)\\b(?:sale|offer|fares? (?:from|starting)|% ?off|discount|coupon|promo|book now|lowest fares?|starting at|starting from|cashback|use code)\\b")

    private fun flightNumber(text: String): Triple<Airline, String, IntRange>? {
        FLIGHT_NUMBER.find(text)?.let { m ->
            val airline = Airlines.byCode(m.groupValues[1]) ?: return@let
            return Triple(airline, m.groupValues[2].trimStart('0').ifEmpty { "0" }, m.range)
        }
        // "your IndiGo flight 2345": the airline in words and the number after "flight".
        val named = NAMED_FLIGHT.find(text) ?: return null
        val airline = Airlines.byNameIn(text) ?: return null
        return Triple(airline, named.groupValues[1], named.range)
    }

    // ---- what happened

    private val CANCELLED = Regex("(?i)\\b(?:is|has been|stands|been|was|are|being)\\s+cancel+ed\\b|\\bcancel+ed\\s+(?:due|by|owing)\\b|\\bcancellation of (?:your |the )?flight|\\bregret.{0,40}\\bcancel")
    private val DELAYED = Regex("(?i)\\bdelay(?:ed|s)?\\b|\\bre-?timed\\b|\\bpre-?poned\\b|\\bnew departure\\b|\\brevised (?:departure|etd)\\b")
    private val RESCHEDULED = Regex("(?i)\\bre-?scheduled\\b|\\bschedule change\\b")
    private val BOARDING_NOW = Regex(
        "(?i)\\bnow boarding\\b|\\bboarding (?:has |have )?(?:started|begun|begins|commenced|closes|gate|call|time|will (?:begin|start|close))|" +
            "\\bboarding (?:at|for|of|starts?)\\b|\\bgate closes?\\b|\\bproceed to (?:the )?gate\\b"
    )
    private val GATE_CHANGE = Regex("(?i)\\bgate\\b.{0,25}\\b(?:changed|revised|shifted|now|moved|updated)\\b|\\bgate change\\b|\\bchanged gate\\b|\\bnew gate\\b")
    private val CHECK_IN = Regex("(?i)\\b(?:web |online |mobile )?check-?in\\b")
    private val REMINDER = Regex("(?i)\\breminder\\b|\\bremind\\b|\\bdeparts (?:today|tomorrow)\\b|\\bdeparting (?:today|tomorrow)\\b|\\bis scheduled to (?:depart|leave)\\b|\\bis departing\\b")
    private val STRONG_BOOKED = Regex("(?i)\\bconfirmed\\b|\\bbooked\\b|\\bissued\\b|\\be-?ticket\\b|\\bitinerary\\b")
    private val CHECK_IN_OPEN = Regex(
        "(?i)check-?in\\s+(?:is\\s+)?(?:now\\s+)?(?:open|started|live|available)\\b|check-?in (?:now|has opened)|open for (?:web )?check-?in|" +
            "\\b(?:do|complete|start) (?:your )?(?:web |online )?check-?in\\b"
    )
    private val BOOKED = Regex("(?i)\\bconfirmed\\b|\\bbooked\\b|\\bbooking\\b|\\bissued\\b|\\be-?ticket\\b|\\bitinerary\\b|\\bticket\\b")

    private fun eventOf(text: String): FlightEvent = when {
        CANCELLED.containsMatchIn(text) -> FlightEvent.CANCELLED
        DELAYED.containsMatchIn(text) -> FlightEvent.DELAYED
        RESCHEDULED.containsMatchIn(text) -> FlightEvent.RESCHEDULED
        GATE_CHANGE.containsMatchIn(text) -> FlightEvent.GATE_CHANGE
        BOARDING_NOW.containsMatchIn(text) -> FlightEvent.BOARDING
        // "Booking confirmed ... web check-in opens 48 hours before" is a booking; "web check-in is open" is the check-in.
        STRONG_BOOKED.containsMatchIn(text) && !CHECK_IN_OPEN.containsMatchIn(text) -> FlightEvent.BOOKED
        CHECK_IN_OPEN.containsMatchIn(text) -> FlightEvent.CHECK_IN
        REMINDER.containsMatchIn(text) -> FlightEvent.REMINDER
        BOOKED.containsMatchIn(text) -> FlightEvent.BOOKED
        else -> FlightEvent.REMINDER
    }

    // ---- where

    private val CITY_ALT = Airports.cityNames.joinToString("|") { Regex.escape(it) }
    private val PAREN_CODE = Regex("\\(([A-Z]{3})\\)")
    private val CODE_PAIR = Regex("(?<![A-Za-z])([A-Z]{3})\\s*(?:-|\u2013|\u2014|\u2192|>|/|\\bto\\b|\\bTO\\b)\\s*([A-Z]{3})(?![A-Za-z])")
    private val CITY_PAIR = Regex("(?i)\\b($CITY_ALT)\\b(?:\\s*\\([A-Z]{3}\\))?\\s*(?:-|\u2013|\u2014|\u2192|>|\\bto\\b)\\s*\\b($CITY_ALT)\\b")

    private fun place(code: String): Place = Place(code, Airports.city(code))

    private fun cityPlace(name: String): Place {
        val code = Airports.iataForCity(name)
        return Place(code, code?.let { Airports.city(it) } ?: name.trim().replaceFirstChar { it.uppercase() })
    }

    private fun route(text: String): Pair<Place?, Place?> {
        val inParens = PAREN_CODE.findAll(text).map { it.groupValues[1] }.filter { Airports.isKnown(it) }.toList()
        if (inParens.size >= 2 && inParens[0] != inParens[1]) return place(inParens[0]) to place(inParens[1])
        CODE_PAIR.findAll(text).forEach { m ->
            val (a, b) = m.destructured
            if (Airports.isKnown(a) && Airports.isKnown(b) && a != b) return place(a) to place(b)
        }
        CITY_PAIR.find(text)?.let { m ->
            val from = cityPlace(m.groupValues[1])
            val to = cityPlace(m.groupValues[2])
            if (from.label != to.label) return from to to
        }
        return null to null
    }

    // ---- when

    private val BOOKED_ON = Regex("(?i)\\b(?:booked|booking date|issued|ticketed|generated|purchased|dated)\\s*(?:on|date|:)?\\s*[:\\-]?\\s*")
    private val DATE_LABEL = Regex("(?i)\\b(?:on|date of (?:journey|travel|departure)|travel date|journey date|departure date|dep date|doj|dated|departs?|departing|departure|dep|for)\\b[^0-9A-Za-z]{0,6}")
    private val TODAY = Regex("(?i)\\btoday\\b")
    private val TOMORROW = Regex("(?i)\\btomorrow\\b")

    /** The day of travel: the date written after "on", "DOJ", "travel date"; never the day it was booked. */
    private fun travelDate(text: String, receivedOn: LocalDate): LocalDate? {
        val cleaned = removeBookingDates(text)
        DATE_LABEL.findAll(cleaned).forEach { label ->
            val rest = cleaned.substring(label.range.last + 1).take(20)
            if (startsWithDate(rest)) LifeParser.dateIn(rest, receivedOn)?.let { return it }
        }
        if (TODAY.containsMatchIn(cleaned)) return receivedOn
        if (TOMORROW.containsMatchIn(cleaned)) return receivedOn.plusDays(1)
        return LifeParser.dateIn(cleaned, receivedOn)
    }

    private val DATE_START = Regex("(?i)^(?:\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2}(?:st|nd|rd|th)?[ -]?[a-z]{3}|[a-z]{3,9} \\d{1,2})")

    private fun startsWithDate(rest: String) = DATE_START.containsMatchIn(rest)

    private fun removeBookingDates(text: String): String {
        val sb = StringBuilder(text)
        BOOKED_ON.findAll(text).toList().asReversed().forEach { m ->
            val tail = text.substring(m.range.last + 1).take(20)
            val date = DATE_START.find(tail)
            if (date != null) sb.replace(m.range.first, m.range.last + 1 + date.range.last + 1, " ")
        }
        return sb.toString()
    }

    private const val CLOCK = "(\\d{1,2}:\\d{2}(?:\\s?(?:am|pm))?|\\d{1,2}\\s?(?:am|pm)|\\d{3,4}\\s?hrs?)"
    private val TIME_TOKEN = Regex("(?i)(?<![\\d:.])$CLOCK(?![\\d:])")
    private const val NEW_WORDS = "new|revised|now|expected|etd|re-?scheduled|re-?timed|pre-?poned|updated"
    private val LABELS = Regex(
        "(?i)\\b(?:(?<new>(?:$NEW_WORDS)(?:\\s+(?:departure|dep|depart|to depart|to leave|time|at|to))*)|" +
            "(?<dep>departs?|departure|departing|dep|std|scheduled|takes? off|leaves|depart)|" +
            "(?<arr>arrives?|arrival|arriving|arr|eta|sta|lands?|landing)|" +
            "(?<skip>boarding|check-?in|gate|report|reach|reporting|closes))\\b"
    )

    private data class Times(val departure: LocalTime?, val arrival: LocalTime?, val newDeparture: LocalTime?)

    private fun clock(token: String): LocalTime? {
        val t = token.trim().lowercase()
        return runCatching {
            when {
                t.endsWith("hr") || t.endsWith("hrs") -> {
                    val digits = t.takeWhile { it.isDigit() }.padStart(4, '0')
                    LocalTime.of(digits.substring(0, 2).toInt(), digits.substring(2, 4).toInt())
                }
                t.endsWith("am") || t.endsWith("pm") -> {
                    val body = t.dropLast(2).trim()
                    val hour = body.substringBefore(':').toInt()
                    val minute = if (':' in body) body.substringAfter(':').toInt() else 0
                    require(hour in 1..12)
                    LocalTime.of(hour % 12 + if (t.endsWith("pm")) 12 else 0, minute)
                }
                else -> LocalTime.of(t.substringBefore(':').toInt(), t.substringAfter(':').toInt())
            }
        }.getOrNull()
    }

    /** Each time belongs to the nearest word before it that names what it is the time of (departure, arrival, a new time). */
    private fun times(text: String, event: FlightEvent, numberRange: IntRange): Times {
        val labels = LABELS.findAll(text).toList()
        var departure: LocalTime? = null
        var arrival: LocalTime? = null
        var newDeparture: LocalTime? = null
        var untagged = 0
        var lone: LocalTime? = null
        TIME_TOKEN.findAll(text).forEach { token ->
            if (token.range.first in numberRange) return@forEach
            val time = clock(token.value) ?: return@forEach
            val label = labels.lastOrNull { it.range.last < token.range.first && token.range.first - it.range.last <= MAX_LABEL_GAP }
            when {
                label == null -> { untagged++; lone = time }
                label.groups["skip"] != null -> Unit
                label.groups["arr"] != null -> if (arrival == null) arrival = time
                label.groups["new"] != null -> if (newDeparture == null) newDeparture = time
                else -> if (departure == null) departure = time
            }
        }
        // A reminder with a single bare time ("flight 6E 2345 at 14:35") is the departure.
        if (departure == null && newDeparture == null && arrival == null && untagged == 1 && event != FlightEvent.BOARDING) departure = lone
        // "Delayed ... departs 15:20" with no scheduled time: the only time given is the new one.
        if (event == FlightEvent.DELAYED && newDeparture == null && departure != null && !SCHEDULED_WORD.containsMatchIn(text)) {
            newDeparture = departure
            departure = null
        }
        // "ETD 02:10" in a booking is just the departure; "new"/"revised" times only mean something for a delay or a reschedule.
        if (event != FlightEvent.DELAYED && event != FlightEvent.RESCHEDULED && departure == null && newDeparture != null) {
            departure = newDeparture
            newDeparture = null
        }
        return Times(departure, arrival, newDeparture)
    }

    private const val MAX_LABEL_GAP = 30
    private val SCHEDULED_WORD = Regex("(?i)\\bscheduled\\b|\\boriginal(?:ly)?\\b|\\bwas\\b|\\bstd\\b|\\binstead of\\b")

    private fun delayMinutes(text: String): Int? {
        val m = Regex("(?i)\\bdelay(?:ed|s)?\\b[^.]{0,30}?(\\d{1,3})\\s*(hours?|hrs?|h\\b|minutes?|mins?|m\\b)(?:\\s*(?:and\\s*)?(\\d{1,2})\\s*(?:minutes?|mins?|m\\b))?").find(text)
            ?: return null
        val n = m.groupValues[1].toInt()
        val unit = m.groupValues[2].lowercase()
        val extra = m.groupValues[3].toIntOrNull() ?: 0
        val minutes = if (unit.startsWith("h")) n * 60 + extra else n
        return minutes.takeIf { it in 1..MAX_DELAY_MINUTES }
    }

    private const val MAX_DELAY_MINUTES = 24 * 60

    // ---- reference, terminal and gate

    private val PNR = Regex(
        "(?i:\\bPNR|booking (?:ref(?:erence)?|id|code)|confirmation (?:code|no\\.?|number)|record locator)\\s*(?:no\\.?|number|is|id|code)?\\s*[:\\-#]?\\s*([A-Z0-9]{6})(?![A-Za-z0-9])"
    )

    private fun pnr(text: String): String? =
        PNR.findAll(text).map { it.groupValues[1] }.firstOrNull { v -> v.any { it.isLetter() } && v != v.lowercase() }

    private val TERMINAL = Regex("(?i)\\bterminal\\s*[:\\-]?\\s*(?:no\\.?\\s*)?T?(\\d[A-Z]?|[A-D])(?![A-Za-z0-9])|(?<![A-Za-z0-9])T([1-4][A-D]?)(?![A-Za-z0-9])")

    private fun terminal(text: String): String? =
        TERMINAL.find(text)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).uppercase() }

    private val GATE = Regex(
        "(?i)\\bgate\\b[^0-9A-Za-z]{0,3}(?:(?:no\\.?|number|is|has been|changed|revised|shifted|moved|now|to|at|updated)\\b[^0-9A-Za-z]{0,3})*([A-Z]?\\d{1,3}[A-Z]?)(?![A-Za-z0-9])"
    )

    private fun gate(text: String): String? = GATE.find(text)?.groupValues?.get(1)?.uppercase()
}
