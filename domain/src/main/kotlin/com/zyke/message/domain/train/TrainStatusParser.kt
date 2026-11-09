package com.zyke.message.domain.train

import com.zyke.message.domain.backup.MiniJson
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** What reading the live source's reply for one run of one train came to. */
sealed interface TrainLookup {
    data class Found(val live: TrainLive) : TrainLookup
    /** A readable reply with no running information for that train on that start date. */
    data object NotFound : TrainLookup
    /** The reply was not what this parser expects (the source changed, or it was an error page). */
    data object Unreadable : TrainLookup
}

/**
 * Reads the live running-status reply for one run of one train.
 *
 * UNVERIFIED AGAINST THE LIVE SERVICE. Every host that could carry this was refused (403) by the build environment's proxy, and there
 * is no official key-less API for Indian Railways, so the reply shape here is the commonly seen one of the unofficial
 * running-status services, written from memory and kept tolerant: a top-level object (optionally inside `data`) with
 * `train_number`, `train_name`, `current_station_code`/`current_station_name`, `status` (free text), `delay` (minutes), `at_station`,
 * and a `route` array of stops (`station_code`, `station_name`, `day`, `sch_arr`, `sch_dep`, `exp_arr`, `exp_dep`, `act_arr`, `act_dep`,
 * `delay_arr`, `delay_dep`, `platform`, `has_arrived`, `has_departed`), times as "HH:mm". The key names have a few aliases below.
 * A captured reply should replace the fixture in `TrainFixtures`. Anything unexpected gives [TrainLookup.Unreadable] and the app
 * keeps the last known status and the schedule from the person's messages.
 */
object TrainStatusParser {

    /** The train number as the source's search takes it: five digits. */
    fun query(train: BookedTrain): String = train.number.filter { it.isDigit() }.take(5)

    /**
     * [startDate] is the day the run started (not necessarily the person's day of journey: a train that left its first station the
     * day before reaches a mid-route station on the next day). The first stop is day 1.
     */
    fun parse(json: String, train: BookedTrain, startDate: LocalDate, fetchedAt: Long): TrainLookup {
        val parsed = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return TrainLookup.Unreadable
        val root = rootOf(parsed)
        val number = text(root, "train_number", "trainNo", "train_no", "number")?.filter { it.isDigit() }
        if (number != null && number.isNotEmpty() && number != train.number) return TrainLookup.Unreadable
        val route = (list(root, *ROUTE_KEYS) ?: list(parsed, "route", "stations", "stops"))
        val statusText = (text(root, "status", "running_status", "message", "status_text")).orEmpty().lowercase()
        val failure = parsed["success"] == false || parsed["error"] != null && route.isNullOrEmpty()
        if (route.isNullOrEmpty() && statusText.isEmpty()) return if (failure || parsed.containsKey("route") || parsed.containsKey("success")) TrainLookup.NotFound else TrainLookup.Unreadable
        val raw = stops(route.orEmpty(), startDate)
        if (route != null && route.isNotEmpty() && raw.isEmpty()) return TrainLookup.Unreadable
        if (failure && raw.isEmpty()) return TrainLookup.NotFound
        return TrainLookup.Found(live(root, raw, statusText, train, fetchedAt))
    }

    // ---- reading

    private val ROUTE_KEYS = arrayOf("route", "stations", "stops", "train_route")
    private val CODE_KEYS = arrayOf("station_code", "stnCode", "stn_code", "code")

    /** The object that holds the run: the top level, inside `data` if there is one, and inside `running_status` if that is an object. */
    private fun rootOf(parsed: Map<*, *>): Map<*, *> {
        val outer = (parsed["data"] as? Map<*, *>) ?: parsed
        return (outer["running_status"] as? Map<*, *>) ?: outer
    }

    /**
     * For the developer's live-status check: the first field this parser needs and cannot find in [json], or null when nothing is
     * missing, when [json] is not JSON at all, or when the reply is the source's readable "no such run" answer. Mirrors [parse].
     */
    fun firstMissingField(json: String): String? {
        val parsedAny = runCatching { MiniJson.parse(json.trim()) }
        if (parsedAny.isFailure) return null
        val parsed = parsedAny.getOrNull() as? Map<*, *> ?: return "top-level object"
        val root = rootOf(parsed)
        val route = list(root, *ROUTE_KEYS) ?: list(parsed, "route", "stations", "stops")
        val statusText = text(root, "status", "running_status", "message", "status_text")
        if (route.isNullOrEmpty()) {
            if (statusText != null) return null
            return if (parsed.containsKey("route") || parsed.containsKey("success")) null else "route (or stations, stops)"
        }
        val first = route.firstOrNull { it is Map<*, *> } as? Map<*, *> ?: return "route entries as objects"
        if (text(first, *CODE_KEYS) == null) return "station_code"
        val anyTime = route.any { item ->
            val o = item as? Map<*, *> ?: return@any false
            listOf("sch_arr", "sch_dep", "act_arr", "act_dep", "scheduled_arrival", "scheduled_departure", "arrival", "departure", "sta", "std").any { clock(o[it]) != null }
        }
        return if (anyTime) null else "sch_arr (or sch_dep)"
    }

    private fun get(map: Map<*, *>?, vararg keys: String): Any? {
        for (k in keys) map?.get(k)?.let { return it }
        return null
    }

    private fun text(map: Map<*, *>?, vararg keys: String): String? = (get(map, *keys) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun list(map: Map<*, *>?, vararg keys: String): List<*>? = get(map, *keys) as? List<*>

    private fun int(map: Map<*, *>?, vararg keys: String): Int? = when (val v = get(map, *keys)) {
        is Number -> v.toInt()
        // "12", "+12", "12 min", "12 mins late"; "On time" and the like are not a number.
        is String -> Regex("^([+-]?\\d{1,4})(?!\\d|:)").find(v.trim())?.groupValues?.get(1)?.removePrefix("+")?.toIntOrNull()
        else -> null
    }

    private fun bool(map: Map<*, *>?, vararg keys: String): Boolean? = when (val v = get(map, *keys)) {
        is Boolean -> v
        is String -> v.equals("true", true) || v == "1" || v.equals("yes", true)
        is Number -> v.toInt() != 0
        else -> null
    }

    /**
     * "21:30", "21:30:00", "21:30 (+1)", "12 Nov 21:30" or "2026-11-12T21:30:00" as a time of day; epoch seconds (or milliseconds), as a
     * number or a string of digits, as that moment on the Indian clock; "--", "Source", "Destination" and the like as null.
     */
    private fun clock(value: Any?): LocalTime? {
        val epoch = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().takeIf { it.length in 9..13 && it.all { c -> c.isDigit() } }?.toLongOrNull()
            else -> null
        }
        if (epoch != null) {
            val seconds = if (epoch > 100_000_000_000L) epoch / 1000 else epoch
            // Anything before 2001 is not a time of a train run.
            if (seconds < 1_000_000_000L) return null
            return Instant.ofEpochSecond(seconds).atZone(Stations.ZONE).toLocalTime().withSecond(0).withNano(0)
        }
        val t = (value as? String)?.trim() ?: return null
        val m = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})").find(t) ?: return null
        return runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }

    private class Raw(val stop: TrainStop, val arrived: Boolean, val departed: Boolean, val delay: Int?)

    private fun stops(route: List<*>, startDate: LocalDate): List<Raw> {
        val out = ArrayList<Raw>()
        var day = 1
        var previous: LocalTime? = null
        for (item in route) {
            val o = item as? Map<*, *> ?: continue
            val code = text(o, *CODE_KEYS)?.uppercase() ?: continue
            val name = text(o, "station_name", "stnName", "stn_name", "name") ?: Stations.name(code) ?: code
            val sa = clock(get(o, "sch_arr", "scheduled_arrival", "arrival", "sta"))
            val sd = clock(get(o, "sch_dep", "scheduled_departure", "departure", "std"))
            val aa = clock(get(o, "act_arr", "actual_arrival"))
            val ad = clock(get(o, "act_dep", "actual_departure"))
            val ea = clock(get(o, "exp_arr", "expected_arrival", "eta"))
            val ed = clock(get(o, "exp_dep", "expected_departure", "etd"))
            val explicitDay = int(o, "day", "day_count", "journey_day")
            val first = sa ?: sd ?: aa ?: ad
            if (explicitDay != null && explicitDay >= 1) day = explicitDay
            else if (previous != null && first != null && first.isBefore(previous)) day += 1
            if (first != null) previous = sd ?: first
            val base = startDate.plusDays((day - 1).toLong())
            val arrivalClock = sa ?: aa
            fun at(t: LocalTime?, arrivalSide: Boolean): Instant? {
                if (t == null) return null
                var d = base
                // A departure earlier than the arrival at the same stop is after midnight.
                if (!arrivalSide && arrivalClock != null && t.isBefore(arrivalClock)) d = d.plusDays(1)
                return d.atTime(t).atZone(Stations.ZONE).toInstant()
            }
            val stop = TrainStop(
                code = code, name = name, day = day,
                scheduledArrival = at(sa, true), scheduledDeparture = at(sd, false),
                expectedArrival = at(ea, true), expectedDeparture = at(ed, false),
                actualArrival = at(aa, true), actualDeparture = at(ad, false),
                platform = text(o, "platform", "pf", "platform_number")?.takeIf { it.any { c -> c.isDigit() } }?.take(4)
            )
            out += Raw(
                stop, bool(o, "has_arrived", "arrived") ?: (aa != null), bool(o, "has_departed", "departed") ?: (ad != null),
                int(o, "delay_dep", "delay_departure", "delay", "late") ?: int(o, "delay_arr", "delay_arrival")
            )
        }
        return out
    }

    private fun live(root: Map<*, *>, raw: List<Raw>, status: String, train: BookedTrain, fetchedAt: Long): TrainLive {
        val stops = raw.map { it.stop }
        val named = text(root, "current_station_code", "current_stn", "last_station_code")?.uppercase()
        // The last stop the train has reached: the source's own word for it, else the last stop flagged as arrived or departed.
        val posIdx = named?.let { c -> stops.indexOfFirst { it.code == c } }?.takeIf { it >= 0 }
            ?: raw.indexOfLast { it.arrived || it.departed }
        val position = raw.getOrNull(posIdx)
        val atStation = bool(root, "at_station", "standing") ?: when {
            position == null -> false
            "arrived at" in status || "standing" in status || "halt" in status -> true
            "departed" in status || "left" in status -> false
            else -> position.arrived && !position.departed
        }
        val cancelled = "cancel" in status && "partial" !in status
        val partial = "cancel" in status && "partial" in status
        val diverted = "divert" in status
        val delay = int(root, "delay", "delay_minutes", "delay_mins", "late_minutes", "late", "late_by") ?: position?.delay ?: 0
        val notStarted = "not started" in status || "yet to start" in status || "yet to depart" in status || "not yet" in status
        val started = !notStarted && (posIdx >= 0 && !(posIdx == 0 && atStation) || "running" in status || "departed" in status)
        val terminated = "terminated" in status || "reached destination" in status || "arrived at destination" in status ||
            (posIdx >= 0 && posIdx == stops.lastIndex && (position?.arrived == true))
        val boardIdx = train.from?.code?.let { c -> stops.indexOfFirst { it.code == c } }?.takeIf { it >= 0 }
        val seenAt = position?.stop?.let { s -> if (position.departed) s.actualDeparture ?: s.actualArrival else s.actualArrival ?: s.actualDeparture }
        return TrainLive(
            trainName = text(root, "train_name", "trainName", "name"), stops = stops,
            lastStationCode = position?.stop?.code ?: named, lastStationName = position?.stop?.name ?: text(root, "current_station_name", "current_stn_name"),
            lastSeenAt = seenAt ?: Instant.ofEpochMilli(fetchedAt).takeIf { posIdx >= 0 },
            delayMinutes = delay, atStation = atStation && !cancelled, started = started && !cancelled,
            cancelled = cancelled, partiallyCancelled = partial, diverted = diverted, terminated = terminated && !cancelled,
            boardingDay = boardIdx?.let { stops[it].day } ?: 1, updatedAt = fetchedAt
        )
    }
}
