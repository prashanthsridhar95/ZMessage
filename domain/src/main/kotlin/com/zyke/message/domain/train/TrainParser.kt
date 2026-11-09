package com.zyke.message.domain.train

import com.zyke.message.domain.flight.Place
import com.zyke.message.domain.life.LifeParser
import java.time.LocalDate
import java.time.LocalTime

/** What a railway text is telling. */
enum class TrainEvent { BOOKED, CHART, PLATFORM, DELAYED, RESCHEDULED, CANCELLED, PARTIALLY_CANCELLED, TICKET_CANCELLED, REMINDER }

/**
 * One text about one train journey, as written. Everything a message did not say is null. [pnr] is kept only to be shown to the
 * person (and copied by them); it is never sent anywhere.
 */
data class TrainFacts(
    /** Five digits: "12633". */
    val number: String,
    val name: String?,
    val date: LocalDate?,
    val from: Place?,
    val to: Place?,
    val departure: LocalTime?,
    val arrival: LocalTime?,
    val trainClass: String?,
    val coach: String?,
    val berth: String?,
    val ticket: String?,
    val pnr: String?,
    val platform: String?,
    val event: TrainEvent,
    val delayMinutes: Int? = null,
    val newDeparture: LocalTime? = null
)

/**
 * Reads Indian Railways texts (IRCTC, Railways, RailYatri, ixigo and the like: booking confirmation, chart prepared, platform,
 * cancellation, reschedule, delay) with rules only, so it behaves the same on every phone. A text with no five-digit train number
 * and a railway word beside it is left alone rather than guessed at. Never throws.
 */
object TrainParser {
    private val SPACES = Regex("\\s+")
    private const val MAX_CHARS = 1_500

    fun parse(body: String, receivedOn: LocalDate): TrainFacts? = try {
        read(body.take(MAX_CHARS).replace(SPACES, " ").trim(), receivedOn)
    } catch (e: Exception) {
        null
    }

    private val CONTEXT = Regex("(?i)\\btrains?\\b|\\btrn\\b|\\birctc\\b|\\brailways?\\b|\\brly\\b|\\bPNR\\b|\\bchart\\b|\\bberth\\b|\\bcoach\\b|\\brailyatri\\b|\\bixigo\\b")
    private val NOT_A_TRAIN = Regex("(?i)\\bflight\\b|\\bairlines?\\b|\\bairways\\b|\\botp\\b|\\bone time password\\b|\\bverification code\\b")
    private val PROMO = Regex("(?i)\\b(?:sale|offer|% ?off|discount|coupon|promo|cashback|use code|book now|lowest fares?)\\b")
    private val NUMBER_LABELLED = Regex("(?i)\\b(?:train|trn)\\s*(?:no\\.?|number|#)?\\s*[:\\-]?\\s*(\\d{5})(?!\\d)")
    private val NUMBER_SLASH_NAME = Regex("(?<![\\d:.-])(\\d{5})\\s*[/\\-]\\s*(?=[A-Za-z]{3})")
    private val NUMBER_PAREN = Regex("\\((\\d{5})\\)")

    private fun read(text: String, receivedOn: LocalDate): TrainFacts? {
        if (!CONTEXT.containsMatchIn(text) || NOT_A_TRAIN.containsMatchIn(text)) return null
        val numberMatch = NUMBER_LABELLED.find(text) ?: NUMBER_SLASH_NAME.find(text) ?: NUMBER_PAREN.find(text) ?: return null
        val number = numberMatch.groupValues[1]
        val pnr = pnr(text)
        if (PROMO.containsMatchIn(text) && pnr == null) return null
        val event = eventOf(text)
        val (from, to) = route(text)
        val times = times(text, event, numberMatch.range)
        val (coach, berth) = coachBerth(text)
        return TrainFacts(
            number = number, name = name(text, numberMatch), date = travelDate(text, receivedOn), from = from, to = to,
            departure = times.departure, arrival = times.arrival, trainClass = trainClass(text), coach = coach, berth = berth,
            ticket = ticket(text), pnr = pnr, platform = platform(text), event = event,
            delayMinutes = if (event == TrainEvent.DELAYED) delayMinutes(text) else null,
            newDeparture = if (event == TrainEvent.DELAYED || event == TrainEvent.RESCHEDULED) times.newDeparture else null
        )
    }

    // ---- what happened

    private val TICKET_CANCELLED = Regex(
        "(?i)\\byour (?:ticket|booking|reservation|pnr)\\b[^.]{0,70}\\bcancel+ed\\b|\\b(?:ticket|booking|pnr)\\b[^.]{0,25}\\b(?:is|has been|was) cancel+ed\\b|" +
            "\\bcancel+ed by (?:you|the passenger)|\\bcancellation charges?\\b|\\bcancellation of (?:your )?(?:ticket|booking|pnr)"
    )
    private val TRAIN_CANCELLED = Regex(
        "(?i)\\btrain\\b[^.]{0,50}\\b(?:is|has been|stands|was|been|being|are)\\s+(?:fully |totally |completely )?cancel+ed\\b|\\bcancel+ed (?:trains?|due to)\\b|\\btrain cancel+ed\\b|\\bregret.{0,40}\\bcancel"
    )
    private val PARTIAL = Regex("(?i)\\bpartial(?:ly)?\\s+cancel+ed\\b|\\bshort[- ]?terminat|\\bterminat(?:ed|ing) at\\b|\\bwill (?:run|start) from\\b.{0,30}\\bonly\\b")
    private val DELAYED = Regex("(?i)\\b(?:running|runs?|is|will (?:be )?running|will run)\\s+(?:late|delayed)\\b|\\blate by\\b|\\bdelay(?:ed|s)?\\b|\\bre-?timed\\b")
    private val RESCHEDULED = Regex("(?i)\\bre-?scheduled\\b|\\bschedule change\\b|\\bpre-?poned\\b|\\bnew departure\\b|\\brevised (?:departure|etd)\\b")
    private val CHART = Regex("(?i)\\bchart(?:ing)?\\s+(?:has been |is |was )?(?:prepared|done|ready)\\b|\\bfinal chart\\b|\\bchart prepared\\b|\\bcurrent status\\b")
    private val PLATFORM_WORDS = Regex("(?i)\\bplatform\\s*(?:no\\.?|number|#)?\\s*[:\\-]?\\s*\\d")
    private val BOOKED = Regex("(?i)\\bconfirmed\\b|\\bbooked\\b|\\bbooking\\b|\\be-?ticket\\b|\\bticket\\b|\\bpnr\\b|\\bbooked\\b")
    private val STRONG_BOOKED = Regex("(?i)\\bbooking (?:is )?confirmed\\b|\\bticket (?:is )?(?:booked|confirmed)\\b|\\be-?ticket\\b|\\bbooked\\b|\\bseat (?:is )?confirmed\\b")

    private fun eventOf(text: String): TrainEvent = when {
        TICKET_CANCELLED.containsMatchIn(text) -> TrainEvent.TICKET_CANCELLED
        PARTIAL.containsMatchIn(text) && Regex("(?i)cancel").containsMatchIn(text) -> TrainEvent.PARTIALLY_CANCELLED
        TRAIN_CANCELLED.containsMatchIn(text) -> TrainEvent.CANCELLED
        DELAYED.containsMatchIn(text) -> TrainEvent.DELAYED
        RESCHEDULED.containsMatchIn(text) -> TrainEvent.RESCHEDULED
        CHART.containsMatchIn(text) -> TrainEvent.CHART
        STRONG_BOOKED.containsMatchIn(text) -> TrainEvent.BOOKED
        PLATFORM_WORDS.containsMatchIn(text) -> TrainEvent.PLATFORM
        BOOKED.containsMatchIn(text) -> TrainEvent.BOOKED
        else -> TrainEvent.REMINDER
    }

    // ---- the name

    private val NAME = Regex(
        "(?i)^[\\s:/\\-(]*([A-Za-z][A-Za-z.&' ]{2,38}?)(?=\\s*(?:[,;.)(]|$)|\\s+(?:on|dated|doj|dep\\w*|from|frm|class|pnr|is|has|have|will|was|at|to|for|date|dt|running|expected|rescheduled|cancel\\w*|delay\\w*|platform|boarding|chart|\\d))"
    )
    private val NOT_NAMES = setOf("is", "on", "to", "at", "for", "has", "was", "will", "dep", "from", "class", "pnr", "dated", "date", "expected")
    private val WORD_FIX = mapOf("exp" to "Express", "sf" to "SF", "spl" to "Special", "jan" to "Jan", "fast" to "Fast", "psgr" to "Passenger", "pass" to "Passenger", "mail" to "Mail")

    private fun name(text: String, number: MatchResult): String? {
        val tail = text.substring(number.range.last + 1).take(60)
        val raw = NAME.find(tail)?.groupValues?.get(1)?.trim()?.trimEnd('.', '-', '/', ',') ?: return null
        if (raw.length < 3 || raw.lowercase() in NOT_NAMES || raw.split(' ').first().lowercase() in NOT_NAMES) return null
        return raw.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            WORD_FIX[w.lowercase().trimEnd('.')] ?: w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // ---- where

    private val NOT_CODES = setOf("TO", "ON", "AT", "IN", "DOJ", "DEP", "ARR", "CLASS", "PNR", "SL", "FROM", "FRM", "THE", "AND", "FOR", "IS", "NO", "NA")
    private val FROM_LABEL = Regex("(?i)\\b(?:from|frm|boarding(?: at| stn| station| point)?|brdg(?: at| stn)?|board(?:ing)? stn)\\b\\s*[:\\-]?\\s*")
    private val TO_LABEL = Regex("(?i)\\b(?:to|dest(?:ination)?|resv upto|reservation upto|upto)\\b\\s*[:\\-]?\\s*")
    private val NAME_ALT = Stations.names.joinToString("|") { Regex.escape(it) }
    private val NAME_AT_START = Regex("(?i)^($NAME_ALT)\\b(?:\\s*\\(([A-Z]{2,5})\\))?")
    private val CODE_AT_START = Regex("^([A-Z]{2,5})(?![A-Za-z])")
    private val CODE_PAIR = Regex("(?<![A-Za-z])([A-Z]{2,5})\\s*(?:-|\u2013|\u2014|\u2192|>|/|\\bto\\b|\\bTO\\b)\\s*([A-Z]{2,5})(?![A-Za-z])")
    private val NAME_PAIR = Regex("(?i)\\b($NAME_ALT)\\b(?:\\s*\\([A-Z]{2,5}\\))?\\s*(?:-|\u2013|\u2014|\u2192|>|\\bto\\b)\\s*\\b($NAME_ALT)\\b")

    private fun stationAt(rest: String): Place? {
        NAME_AT_START.find(rest)?.let { m ->
            val code = m.groupValues[2].ifEmpty { null } ?: Stations.codeForName(m.groupValues[1])
            return if (code != null) Place(code, Stations.name(code) ?: Stations.placeForName(m.groupValues[1]).city) else Stations.placeForName(m.groupValues[1])
        }
        CODE_AT_START.find(rest)?.let { m ->
            val c = m.groupValues[1]
            if (c !in NOT_CODES) return Stations.place(c)
        }
        return null
    }

    private fun route(text: String): Pair<Place?, Place?> {
        var from: Place? = null
        var to: Place? = null
        FROM_LABEL.findAll(text).forEach { m -> if (from == null) from = stationAt(text.substring(m.range.last + 1).take(40)) }
        // The destination is the label after the origin (a "to" inside the origin's name is not one).
        val afterFrom = FROM_LABEL.find(text)?.range?.last ?: 0
        TO_LABEL.findAll(text).filter { it.range.first >= afterFrom }.forEach { m -> if (to == null) to = stationAt(text.substring(m.range.last + 1).take(40)) }
        if (from != null && to != null && from!!.label != to!!.label) return from to to
        CODE_PAIR.findAll(text).forEach { m ->
            val (a, b) = m.destructured
            if (Stations.isKnown(a) && Stations.isKnown(b) && a != b) return Stations.place(a) to Stations.place(b)
        }
        NAME_PAIR.find(text)?.let { m ->
            val a = Stations.placeForName(m.groupValues[1])
            val b = Stations.placeForName(m.groupValues[2])
            if (a.label != b.label) return a to b
        }
        // "at MS", "expected departure from MS": only one end is stated.
        if (from != null) return from to null
        Regex("\\b(?:at|from)\\s+($NAME_ALT|[A-Z]{2,5})(?![A-Za-z])", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            stationAt(m.groupValues[1])?.takeIf { it.code != null && Stations.isKnown(it.code) }?.let { return it to null }
        }
        return null to null
    }

    // ---- when

    private val BOOKED_ON = Regex("(?i)\\b(?:booked|booking date|issued|ticketed|generated|purchased|txn|transaction)\\s*(?:on|date|dt|:)?\\s*[:\\-]?\\s*")
    private val DATE_LABEL = Regex("(?i)\\b(?:doj|date of journey|journey date|travel date|dt of journey|dept date|dep date|journey on|dated|on|date|dt)\\b[^0-9A-Za-z]{0,6}")
    private val DATE_START = Regex("(?i)^(?:\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|\\d{1,2}(?:st|nd|rd|th)?[ -]?[a-z]{3}|[a-z]{3,9} \\d{1,2})")
    private val TODAY = Regex("(?i)\\btoday\\b")
    private val TOMORROW = Regex("(?i)\\btomorrow\\b")

    private fun travelDate(text: String, receivedOn: LocalDate): LocalDate? {
        val cleaned = removeBookingDates(text)
        DATE_LABEL.findAll(cleaned).forEach { label ->
            val rest = cleaned.substring(label.range.last + 1).take(20)
            if (DATE_START.containsMatchIn(rest)) LifeParser.dateIn(rest, receivedOn)?.let { return it }
        }
        if (TODAY.containsMatchIn(cleaned)) return receivedOn
        if (TOMORROW.containsMatchIn(cleaned)) return receivedOn.plusDays(1)
        return LifeParser.dateIn(cleaned, receivedOn)
    }

    private fun removeBookingDates(text: String): String {
        val sb = StringBuilder(text)
        BOOKED_ON.findAll(text).toList().asReversed().forEach { m ->
            val tail = text.substring(m.range.last + 1).take(20)
            val date = DATE_START.find(tail)
            if (date != null) sb.replace(m.range.first, m.range.last + 1 + date.range.last + 1, " ")
        }
        return sb.toString()
    }

    private val TIME_TOKEN = Regex("(?<![\\d:.])(\\d{1,2}:\\d{2}|\\d{4}\\s?hrs?)(?![\\d:])", RegexOption.IGNORE_CASE)
    private val LABELS = Regex(
        "(?i)\\b(?:(?<new>(?:new|revised|now|expected|etd|rescheduled|re-?timed|pre-?poned|updated)(?:\\s+(?:departure|dep|depart|time|at|to|for))*)|" +
            "(?<dep>departs?|departure|departing|dep|dept|std|scheduled|leaves|boarding)|(?<arr>arrives?|arrival|arriving|arr|eta|sta|reach(?:es)?))\\b"
    )

    private data class Times(val departure: LocalTime?, val arrival: LocalTime?, val newDeparture: LocalTime?)

    private fun clock(token: String): LocalTime? {
        val t = token.trim().lowercase()
        return runCatching {
            if (t.endsWith("hr") || t.endsWith("hrs")) {
                val digits = t.takeWhile { it.isDigit() }.padStart(4, '0')
                LocalTime.of(digits.substring(0, 2).toInt(), digits.substring(2, 4).toInt())
            } else LocalTime.of(t.substringBefore(':').toInt(), t.substringAfter(':').toInt())
        }.getOrNull()
    }

    private fun times(text: String, event: TrainEvent, numberRange: IntRange): Times {
        val labels = LABELS.findAll(text).toList()
        var departure: LocalTime? = null
        var arrival: LocalTime? = null
        var newDeparture: LocalTime? = null
        var untagged = 0
        var lone: LocalTime? = null
        TIME_TOKEN.findAll(text).forEach { token ->
            if (token.range.first in numberRange) return@forEach
            val time = clock(token.value) ?: return@forEach
            val label = labels.lastOrNull { it.range.last < token.range.first && token.range.first - it.range.last <= 30 }
            when {
                label == null -> { untagged++; lone = time }
                label.groups["arr"] != null -> if (arrival == null) arrival = time
                label.groups["new"] != null -> if (newDeparture == null) newDeparture = time
                else -> if (departure == null) departure = time
            }
        }
        if (departure == null && newDeparture == null && arrival == null && untagged == 1) departure = lone
        // "Delayed ... departure 22:15" with no scheduled time: the only time given is the new one.
        if (event == TrainEvent.DELAYED && newDeparture == null && departure != null && !SCHEDULED_WORD.containsMatchIn(text)) {
            newDeparture = departure
            departure = null
        }
        if (event != TrainEvent.DELAYED && event != TrainEvent.RESCHEDULED && departure == null && newDeparture != null) {
            departure = newDeparture
            newDeparture = null
        }
        return Times(departure, arrival, newDeparture)
    }

    private val SCHEDULED_WORD = Regex("(?i)\\bscheduled\\b|\\boriginal(?:ly)?\\b|\\bwas\\b|\\bstd\\b|\\binstead of\\b")

    private fun delayMinutes(text: String): Int? {
        val m = Regex("(?i)\\b(?:late by|delay(?:ed)?(?: by| of)?|late for)\\b[^.]{0,20}?(\\d{1,3})\\s*(hours?|hrs?|h\\b|minutes?|mins?|m\\b)(?:\\s*(?:and\\s*)?(\\d{1,2})\\s*(?:minutes?|mins?|m\\b))?").find(text) ?: return null
        val n = m.groupValues[1].toInt()
        val extra = m.groupValues[3].toIntOrNull() ?: 0
        val minutes = if (m.groupValues[2].lowercase().startsWith("h")) n * 60 + extra else n
        return minutes.takeIf { it in 1..(48 * 60) }
    }

    // ---- reference, class, coach, berth

    private val PNR = Regex("(?i)\\bPNR\\s*(?:no\\.?|number|#|is)?\\s*[:\\-]?\\s*(\\d{10})(?!\\d)")
    private fun pnr(text: String): String? = PNR.find(text)?.groupValues?.get(1)

    private val CLASS_LABELLED = Regex("(?i)\\b(?:class|cls|cl)\\s*[:\\-]?\\s*(SL|3A|2A|1A|CC|2S|3E|EC|EA|FC)(?![A-Za-z0-9])")
    private val CLASS_BARE = Regex("(?<![A-Za-z0-9])(SL|3A|2A|1A|CC|2S|3E|EC)(?![A-Za-z0-9])")
    private fun trainClass(text: String): String? =
        CLASS_LABELLED.find(text)?.groupValues?.get(1)?.uppercase() ?: CLASS_BARE.find(text)?.groupValues?.get(1)

    private val BERTH = Regex(
        "(?<![A-Za-z0-9])([SBAHMCDE]\\d{1,2})\\s*[,/\\-\\s]\\s*(\\d{1,3})(?:\\s*[,/\\-\\s]\\s*(LB|MB|UB|SL|SU|LOWER|MIDDLE|UPPER|SIDE LOWER|SIDE UPPER|WS|W|AB|CAB))?(?![A-Za-z0-9])"
    )

    private fun coachBerth(text: String): Pair<String?, String?> {
        val m = BERTH.find(text) ?: return null to null
        val place = m.groupValues[3].uppercase()
        val short = when (place) { "LOWER" -> "LB"; "MIDDLE" -> "MB"; "UPPER" -> "UB"; "SIDE LOWER" -> "SL"; "SIDE UPPER" -> "SU"; else -> place }
        return m.groupValues[1].uppercase() to listOf(m.groupValues[2], short).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val TICKET = Regex("(?i)(?<![A-Za-z])(CNF|RAC|(?:RL|PQ|GN|RS|TQ|RQ)?WL)(?![A-Za-z])\\s*/?\\s*(\\d{1,3})?")

    /** The newest status in the text: "CNF", "RAC 12", "WL 5" ("WL12/WL8" is waiting 12 at booking, 8 now: 8). */
    private fun ticket(text: String): String? {
        val last = TICKET.findAll(text).lastOrNull() ?: return null
        val kind = last.groupValues[1].uppercase().let { if (it.endsWith("WL")) "WL" else it }
        val n = last.groupValues[2]
        return if (kind == "CNF" || n.isEmpty()) kind else "$kind $n"
    }

    private val PLATFORM = Regex("(?i)\\bplatform\\s*(?:no\\.?|number|#)?\\s*[:\\-]?\\s*(\\d{1,2}[A-Z]?)(?![A-Za-z0-9])")
    private fun platform(text: String): String? = PLATFORM.find(text)?.groupValues?.get(1)?.uppercase()
}
