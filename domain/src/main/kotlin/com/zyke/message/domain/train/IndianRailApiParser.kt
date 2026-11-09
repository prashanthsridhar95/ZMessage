package com.zyke.message.domain.train

import com.zyke.message.domain.backup.MiniJson
import com.zyke.message.domain.diag.LiveFailure
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** What reading the provider's reply for one run of one train came to. */
sealed interface TrainLookup {
    data class Found(val live: TrainLive) : TrainLookup
    /** A readable reply with no running information for that train on that start date. */
    data object NotFound : TrainLookup
    /** The reply was not what this parser expects (the provider changed, or it was an error page). */
    data object Unreadable : TrainLookup
    /**
     * The provider answered with a response code other than 200 that is not "no data": [failure] is [LiveFailure.KEY_REJECTED],
     * [LiveFailure.LIMIT_REACHED] or [LiveFailure.PROVIDER] (anything the app does not know).
     */
    data class Refused(val failure: LiveFailure) : TrainLookup
}

/**
 * Reads the reply of "Live Train Status" (Indian Rail API, indianrailapi.com) for one run of one train.
 *
 * Written from the provider's documentation page (its example reply, dated 2018, is the fixture `TrainFixtures.documentationSample`):
 * `ResponseCode` (a string, "200" for success), `StartDate` ("dd-MM-yyyy"), `TrainNumber`, `CurrentPosition` (may be null),
 * `CurrentStation` (the last station reached or crossed, with `StationCode`, `StationName`, `ScheduleArrival`, `ActualArrival`,
 * `DelayInArrival`, `ScheduleDeparture`, `ActualDeparture`, `DelayInDeparture`, `IsDeparted`, `Day`), `TrainRoute` (the same fields for
 * every stop) and `Message`. Times are 12-hour "hh:mmAM" strings with no date, "Source" and "Destination" stand in for the missing
 * arrival at the first stop and departure at the last, delays are strings like "14 M".
 *
 * NOT VERIFIED AGAINST THE LIVE SERVICE: the build environment cannot reach it, the example is from 2018, and the response codes other
 * than "200" are not documented there. Anything unexpected gives [TrainLookup.Unreadable] (or [TrainLookup.Refused] for a response code
 * it does not know), and the app keeps the schedule from the person's messages.
 *
 * What the reply does not say stays unknown: it has no cancelled, diverted or platform information, so those are never set.
 */
object IndianRailApiParser {
    private const val SUCCESS = "200"
    private val START_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    /**
     * [startDate] is the day the run started (not necessarily the person's day of journey: a train that left its first station the day
     * before reaches a mid-route station on the next day). The first stop is day 1.
     */
    fun parse(json: String, train: BookedTrain, startDate: LocalDate, fetchedAt: Long): TrainLookup {
        val root = runCatching { MiniJson.parse(json.trim()) }.getOrNull() as? Map<*, *> ?: return TrainLookup.Unreadable
        val code = responseCode(root) ?: return TrainLookup.Unreadable
        if (code != SUCCESS) return refusal(root)
        val number = text(root["TrainNumber"])?.filter { it.isDigit() }
        if (!number.isNullOrEmpty() && number != train.number) return TrainLookup.Unreadable
        val route = root["TrainRoute"] as? List<*> ?: return TrainLookup.Unreadable
        if (route.isEmpty()) return TrainLookup.NotFound
        // A reply that names another start date is the answer for another run.
        val replyStart = text(root["StartDate"])?.let { runCatching { LocalDate.parse(it, START_DATE) }.getOrNull() }
        if (replyStart != null && replyStart != startDate) return TrainLookup.NotFound
        val rows = route.mapNotNull { row(it) }
        if (rows.isEmpty()) return TrainLookup.Unreadable
        return TrainLookup.Found(live(root, rows, train, startDate, fetchedAt))
    }

    /**
     * For the developer's live-status check: the first field this parser needs and cannot find in [json], or null when nothing is
     * missing, when [json] is not JSON at all, or when the reply is the provider's own error or "no data" answer. Mirrors [parse].
     */
    fun firstMissingField(json: String): String? {
        val parsedAny = runCatching { MiniJson.parse(json.trim()) }
        if (parsedAny.isFailure) return null
        val root = parsedAny.getOrNull() as? Map<*, *> ?: return "top-level object"
        val code = responseCode(root) ?: return "ResponseCode"
        if (code != SUCCESS) return null
        val route = root["TrainRoute"] as? List<*> ?: return "TrainRoute"
        if (route.isEmpty()) return null
        val first = route.firstOrNull { it is Map<*, *> } as? Map<*, *> ?: return "TrainRoute entries as objects"
        if (text(first["StationCode"]) == null) return "StationCode"
        val anyTime = route.any { item ->
            val o = item as? Map<*, *> ?: return@any false
            clock(text(o["ScheduleArrival"])) != null || clock(text(o["ScheduleDeparture"])) != null
        }
        return if (anyTime) null else "ScheduleArrival (or ScheduleDeparture)"
    }

    /** The provider's own response code and message on one short line, for the developer's check only; null when there are none. */
    fun providerNote(json: String): String? {
        val root = runCatching { MiniJson.parse(json.trim()) }.getOrNull() as? Map<*, *> ?: return null
        val code = responseCode(root)
        val message = text(root["Message"])?.let { oneLine(it, MESSAGE_CHARS) }
        if (code == null && message == null) return null
        return listOfNotNull(code?.let { "ResponseCode $it" }, message?.let { "Message \"$it\"" }).joinToString(" · ")
    }

    // ---- reading

    private const val MESSAGE_CHARS = 80

    private fun responseCode(root: Map<*, *>): String? = when (val v = root["ResponseCode"]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long -> v.toString()
        is Double -> v.toLong().toString()
        else -> null
    }

    private fun text(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** [s] on one line (control characters and runs of white space become one space), cut to [max] characters. */
    internal fun oneLine(s: String, max: Int): String {
        val flat = s.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").replace(Regex(" +"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    /**
     * What a response code other than 200 means. The documentation lists none, so the provider's own Message decides, by keywords: a
     * limit or quota, a key problem, or no data for that train. Anything else is [LiveFailure.PROVIDER].
     */
    private fun refusal(root: Map<*, *>): TrainLookup {
        val message = text(root["Message"])?.lowercase().orEmpty()
        return when {
            LIMIT_WORDS.any { it in message } -> TrainLookup.Refused(LiveFailure.LIMIT_REACHED)
            KEY_WORDS.any { it in message } -> TrainLookup.Refused(LiveFailure.KEY_REJECTED)
            NO_DATA_WORDS.any { it in message } -> TrainLookup.NotFound
            else -> TrainLookup.Refused(LiveFailure.PROVIDER)
        }
    }

    private val LIMIT_WORDS = listOf("limit", "quota", "exceed", "too many")
    private val KEY_WORDS = listOf("api key", "apikey", "invalid key", "key is", "unauthor", "authenticat", "not authori", "subscription")
    private val NO_DATA_WORDS = listOf("no data", "not found", "no record", "not running", "no such", "does not run", "no running", "not available", "no status")

    /** "04:27PM", "12:05AM", "9:15 am" as a time of day; "Source", "Destination", "-" and anything else as null. */
    fun clock(value: String?): LocalTime? {
        val m = CLOCK.matchEntire(value?.trim() ?: return null) ?: return null
        val hour12 = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour12 !in 1..12 || minute !in 0..59) return null
        val pm = m.groupValues[3].startsWith("p", ignoreCase = true)
        return LocalTime.of(hour12 % 12 + if (pm) 12 else 0, minute)
    }

    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])")

    /**
     * "14 M", "00 M", "75 M" as minutes; "1 H 5 M" as 65 and "-5 M" as -5 (early). "-", empty and anything this does not know are
     * null: the delay stays unknown rather than guessed.
     */
    fun delayMinutes(value: String?): Int? {
        val m = DELAY.matchEntire(value?.trim() ?: return null) ?: return null
        val hours = m.groupValues[2]
        val minutes = m.groupValues[3]
        if (hours.isEmpty() && minutes.isEmpty()) return null
        val total = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
        return if (m.groupValues[1] == "-") -total else total
    }

    private val DELAY = Regex("(-)?\\s*(?:(\\d{1,2})\\s*[Hh])?\\s*(?:(\\d{1,4})\\s*[Mm])?")

    private class Row(
        val code: String, val name: String, val dayHint: Int?,
        val sa: LocalTime?, val sd: LocalTime?, val aa: LocalTime?, val ad: LocalTime?,
        val delayArr: Int?, val delayDep: Int?, val departedFlag: Boolean?
    ) {
        var arrDay = 0
        var depDay = 0
        var schedArr: Instant? = null
        var schedDep: Instant? = null
        var repArr: Instant? = null
        var repDep: Instant? = null
    }

    private fun row(item: Any?): Row? {
        val o = item as? Map<*, *> ?: return null
        val code = text(o["StationCode"])?.uppercase() ?: return null
        return Row(
            code = code, name = text(o["StationName"]) ?: Stations.name(code) ?: code,
            dayHint = text(o["Day"])?.toIntOrNull(),
            sa = clock(text(o["ScheduleArrival"])), sd = clock(text(o["ScheduleDeparture"])),
            aa = clock(text(o["ActualArrival"])), ad = clock(text(o["ActualDeparture"])),
            delayArr = delayMinutes(text(o["DelayInArrival"])), delayDep = delayMinutes(text(o["DelayInDeparture"])),
            departedFlag = when (text(o["IsDeparted"])?.lowercase()) {
                "y", "yes", "true", "1" -> true
                "n", "no", "false", "0" -> false
                else -> null
            }
        )
    }

    /**
     * The times have no date, so the day of each stop comes from the order of the schedule: a time earlier than the one before it is
     * after midnight, and so is a departure earlier than the arrival at the same stop. The `Day` field only moves a stop later (the
     * sample reply says "0" for every stop, even past midnight), counted from the first stop's own value.
     */
    private fun assignDays(rows: List<Row>, start: LocalDate) {
        val firstHint = rows.firstNotNullOfOrNull { it.dayHint } ?: 0
        var day = 0
        var previous: LocalTime? = null
        for (r in rows) {
            val first = r.sa ?: r.sd
            if (previous != null && first != null && first.isBefore(previous)) day += 1
            r.dayHint?.let { hint -> if (hint - firstHint > day) day = hint - firstHint }
            r.arrDay = day
            if (r.sa != null && r.sd != null && r.sd.isBefore(r.sa)) day += 1
            r.depDay = day
            previous = r.sd ?: r.sa ?: previous
            stamp(r, start)
        }
    }

    /** Turns a row's days and clock times into moments. */
    private fun stamp(r: Row, start: LocalDate) {
        val arrivalDay = start.plusDays(r.arrDay.toLong())
        val departureDay = start.plusDays(r.depDay.toLong())
        r.schedArr = r.sa?.let { arrivalDay.atTime(it).atZone(Stations.ZONE).toInstant() }
        r.schedDep = r.sd?.let { departureDay.atTime(it).atZone(Stations.ZONE).toInstant() }
        // A reported time is the scheduled one plus a delay: the day (this one, the one before or the one after) that puts it nearest.
        r.repArr = r.aa?.let { nearest(it, r.schedArr ?: r.schedDep, arrivalDay) }
        r.repDep = r.ad?.let { nearest(it, r.schedDep ?: r.schedArr, departureDay) }
    }

    private fun nearest(t: LocalTime, around: Instant?, fallbackDay: LocalDate): Instant {
        if (around == null) return fallbackDay.atTime(t).atZone(Stations.ZONE).toInstant()
        val day = around.atZone(Stations.ZONE).toLocalDate()
        return (-1L..1L).map { day.plusDays(it).atTime(t).atZone(Stations.ZONE).toInstant() }
            .minByOrNull { abs(Duration.between(around, it).toMinutes()) } ?: around
    }

    private fun live(root: Map<*, *>, parsed: List<Row>, train: BookedTrain, start: LocalDate, fetchedAt: Long): TrainLive {
        val rows = parsed.toMutableList()
        assignDays(rows, start)
        val fetched = Instant.ofEpochMilli(fetchedAt)
        var pos = -1
        val cur = (root["CurrentStation"] as? Map<*, *>)?.let { row(it) }
        if (cur != null) {
            val at = rows.indexOfFirst { it.code == cur.code }
            if (at >= 0) {
                pos = at
                rows[at] = merged(rows[at], cur, start)
            } else {
                // The route the provider sends can leave a station out: the current one is placed by its schedule.
                val index = placeByTime(cur, rows, start, fetched)
                if (index != null && index < rows.size) {
                    rows.add(index, cur)
                    pos = index
                } else if (index != null) {
                    pos = rows.lastIndex
                }
            }
        }
        val here = rows.getOrNull(pos)
        val departed = here?.let { r -> r.departedFlag ?: r.repDep?.let { !it.isAfter(fetched) } ?: false } ?: false
        val last = pos >= 0 && pos == rows.lastIndex
        val stops = rows.mapIndexed { i, r -> stop(r, i, pos, departed) }
        val delay = when {
            here == null -> rows.firstOrNull()?.let { it.delayDep ?: it.delayArr }
            departed -> here.delayDep ?: here.delayArr ?: gap(here.schedDep, here.repDep) ?: gap(here.schedArr, here.repArr)
            else -> here.delayArr ?: here.delayDep ?: gap(here.schedArr, here.repArr) ?: gap(here.schedDep, here.repDep)
        } ?: 0
        val boardIdx = train.from?.code?.let { c -> rows.indexOfFirst { it.code.equals(c, ignoreCase = true) } }?.takeIf { it >= 0 }
        val seenAt = here?.let { r -> if (departed) r.repDep ?: r.repArr else r.repArr ?: r.repDep }
        return TrainLive(
            trainName = null, stops = stops,
            lastStationCode = here?.code, lastStationName = here?.name,
            lastSeenAt = seenAt ?: fetched.takeIf { here != null },
            delayMinutes = delay, atStation = here != null && !departed && !last,
            started = here != null && !(pos == 0 && !departed),
            terminated = last, boardingDay = boardIdx?.let { stops[it].day } ?: 1, updatedAt = fetchedAt
        )
    }

    private fun gap(scheduled: Instant?, reported: Instant?): Int? =
        if (scheduled == null || reported == null) null else Duration.between(scheduled, reported).toMinutes().toInt()

    /** The route's row for the current station, with whatever the `CurrentStation` entry reports taking precedence. */
    private fun merged(inRoute: Row, current: Row, start: LocalDate): Row {
        val r = Row(
            inRoute.code, inRoute.name, inRoute.dayHint, inRoute.sa ?: current.sa, inRoute.sd ?: current.sd,
            current.aa ?: inRoute.aa, current.ad ?: inRoute.ad,
            current.delayArr ?: inRoute.delayArr, current.delayDep ?: inRoute.delayDep, current.departedFlag ?: inRoute.departedFlag
        )
        r.arrDay = inRoute.arrDay
        r.depDay = inRoute.depDay
        stamp(r, start)
        return r
    }

    /**
     * Where a station that is not in the route sits in it, by its schedule: the index to insert [cur] at (the row count means "after
     * the last stop"), or null when it cannot be placed. The day is the one on which its reported time is nearest the moment the reply
     * was fetched, since the train reached it just before that. Sets [cur]'s days and moments.
     */
    private fun placeByTime(cur: Row, rows: List<Row>, start: LocalDate, fetched: Instant): Int? {
        val sched = cur.sa ?: cur.sd ?: return null
        val seenClock = cur.aa ?: cur.ad ?: sched
        val today = fetched.atZone(Stations.ZONE).toLocalDate()
        val seen = (-1L..1L).map { today.plusDays(it).atTime(seenClock).atZone(Stations.ZONE).toInstant() }
            .minByOrNull { abs(Duration.between(fetched, it).toMinutes()) } ?: return null
        val schedInstant = nearest(sched, seen, today)
        val before = rows.count { r -> (r.schedDep ?: r.schedArr)?.let { !it.isAfter(schedInstant) } ?: false }
        if (before == 0) return null
        val date = schedInstant.atZone(Stations.ZONE).toLocalDate()
        cur.arrDay = Duration.between(start.atStartOfDay(Stations.ZONE), date.atStartOfDay(Stations.ZONE)).toDays().toInt().coerceAtLeast(0)
        cur.depDay = cur.arrDay + if (cur.sa != null && cur.sd != null && cur.sd.isBefore(cur.sa)) 1 else 0
        stamp(cur, start)
        return before
    }

    /** A reached stop has actual times; a stop still ahead has the provider's times as expected ones. */
    private fun stop(r: Row, index: Int, pos: Int, departedHere: Boolean): TrainStop {
        val reachedArrival = pos >= 0 && index <= pos
        val reachedDeparture = pos >= 0 && (index < pos || index == pos && departedHere)
        return TrainStop(
            code = r.code, name = r.name, day = (if (r.sa != null) r.arrDay else r.depDay) + 1,
            scheduledArrival = r.schedArr, scheduledDeparture = r.schedDep,
            expectedArrival = r.repArr.takeIf { !reachedArrival }, expectedDeparture = r.repDep.takeIf { !reachedDeparture },
            actualArrival = r.repArr.takeIf { reachedArrival }, actualDeparture = r.repDep.takeIf { reachedDeparture },
            platform = null
        )
    }
}
