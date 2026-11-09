package com.zyke.message.domain.train

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Sample texts and replies for the train tests. The texts are invented in the wording IRCTC, Indian Railways and the booking apps use
 * (every number, PNR and name is made up). [documentationSample] is the one example reply from the provider's own documentation page
 * (Live Train Status, Indian Rail API; the example there is dated 2018), kept verbatim. The other replies are HAND-BUILT in that same
 * shape for the 12633 journey the tests use; none is captured from the live service, which the build environment cannot reach.
 * Replace them with a captured reply when a device with internet is at hand.
 */
internal object TrainFixtures {
    val DOJ: LocalDate = LocalDate.of(2026, 11, 12)

    fun ist(day: Int, hour: Int, minute: Int, month: Int = 11): Instant =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(Stations.ZONE).toInstant()

    const val IRCTC_BOOKING = "PNR: 1234567890, TRAIN: 12633, DOJ: 12-11-2026, DEP: 21:30, CLASS: SL, FROM: MS TO: TPJ, FARE: Rs.455. Passenger 1: S4 32 SU - CNF. -IRCTC"
    const val WORDS_BOOKING = "Booking confirmed. PNR 1234567890. Train 12633 KANYAKUMARI EXP on 12-Nov-2026. Chennai Egmore (MS) to Tiruchirappalli (TPJ). Dep 21:30 Arr 04:35. SL, S4/32/SU. -IRCTC"
    const val CHART = "Chart prepared for train 12633 on 12-11-2026. PNR 1234567890: Current status S4 33 LB CNF. Boarding MS dep 21:30."
    const val PLATFORM = "Train 12633 is expected at platform no. 4 at MS."
    const val DELAY = "Train 12633 (12-11-2026) is running late by 45 min. Expected departure 22:15 from MS."
    const val RESCHEDULED = "Train 12633 of 12-11-2026 has been rescheduled. New departure time 23:10 from MS."
    const val TRAIN_CANCELLED = "Train 12633 of 12-11-2026 is cancelled. Full refund will be given for PNR 1234567890."
    const val TICKET_CANCELLED = "Your ticket PNR 1234567890 is cancelled. Refund of Rs.380 will be credited. -IRCTC"
    const val WAITLIST = "PNR 2234567890 booked. Train 12633, DOJ 12-11-2026, SL, MS-TPJ, Dep 21:30, status WL12/WL8. -IRCTC"

    /** "21:30" as the provider writes a time: "09:30PM". */
    fun h12(hhmm: String): String {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return "%02d:%02d%s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }

    private fun q(v: String?, edge: String): String = "\"" + (v?.let { h12(it) } ?: edge) + "\""

    /**
     * One stop of the route in the provider's shape. Times are 24-hour here and written 12-hour; [sa] null is "Source", [sd] null is
     * "Destination". The reported (actual or expected) times default to the schedule; "Day" is "0" throughout, as in the sample.
     */
    fun stop(
        serial: Int, code: String, name: String, sa: String?, sd: String?, aa: String? = sa, ad: String? = sd,
        delayArr: String = "00 M", delayDep: String = "00 M"
    ): String = """{"SerialNo":"$serial","StationName":"$name","StationCode":"$code","Distance":"-","IsDeparted":"-","Day":"0",""" +
        """"ScheduleArrival":${q(sa, "Source")},"ActualArrival":${q(aa, "Source")},"DelayInArrival":"${if (sa == null) "-" else delayArr}",""" +
        """"ScheduleDeparture":${q(sd, "Destination")},"ActualDeparture":${q(ad, "Destination")},"DelayInDeparture":"${if (sd == null) "-" else delayDep}"}"""

    private fun reply(start: String, current: String?, route: List<String>, number: String = "12633") =
        """{"ResponseCode":"200","StartDate":"$start","TrainNumber":"$number","CurrentPosition":null,"CurrentStation":${current ?: "null"},""" +
            """"TrainRoute":[${route.joinToString(",")}],"Message":"SUCCESS"}"""

    private fun late(n: Int) = "$n M"

    private val msStop = stop(0, "MS", "Chennai Egmore", null, "21:30", ad = "21:42", delayDep = late(12))

    /** Running: left MS 12 minutes late, now past Villupuram, whose times are the current station's. Stops ahead carry expected times. */
    val running = reply(
        "12-11-2026",
        stop(3, "VM", "Villupuram Jn", "23:40", "23:42", "23:52", "23:54", late(12), late(12)),
        listOf(
            msStop,
            stop(1, "TBM", "Tambaram", "22:00", "22:02", "22:12", "22:14", late(12), late(12)),
            stop(2, "CGL", "Chengalpattu", "22:25", "22:27", "22:37", "22:39", late(12), late(12)),
            stop(3, "VM", "Villupuram Jn", "23:40", "23:42", "23:52", "23:54", late(12), late(12)),
            stop(4, "VRI", "Vriddhachalam Jn", "00:45", "00:47", "00:57", "00:59", late(12), late(12)),
            stop(5, "ALU", "Ariyalur", "01:35", "01:37", "01:47", "01:49", late(12), late(12)),
            stop(6, "TPJ", "Tiruchirappalli Jn", "04:35", null, "04:47", null, late(12))
        )
    )

    /** No current station yet: the train has not started. */
    val notStarted = reply(
        "12-11-2026", null,
        listOf(stop(0, "MS", "Chennai Egmore", null, "21:30"), stop(1, "TPJ", "Tiruchirappalli Jn", "04:35", null))
    )

    /** Arrived at Tiruchirappalli at 04:41 on the 13th, 6 minutes late. */
    val arrived = reply(
        "12-11-2026",
        stop(2, "TPJ", "Tiruchirappalli Jn", "04:35", null, "04:41", null, late(6)),
        listOf(
            stop(0, "MS", "Chennai Egmore", null, "21:30", ad = "21:36", delayDep = late(6)),
            stop(1, "VM", "Villupuram Jn", "23:40", "23:42", "23:48", "23:50", late(8), late(8)),
            stop(2, "TPJ", "Tiruchirappalli Jn", "04:35", null, "04:41", null, late(6))
        )
    )

    /** A hand-built "no data" answer: the response code and message are made up, since the documentation lists none besides 200. */
    val noData = """{"ResponseCode":"204","Message":"No data found for this train on this date"}"""
    val keyRejected = """{"ResponseCode":"401","Message":"Invalid API key"}"""
    val limitReached = """{"ResponseCode":"429","Message":"Daily request limit exceeded"}"""
    val unknownRefusal = """{"ResponseCode":"500","Message":"Something went wrong"}"""
    val otherTrain = reply("12-11-2026", null, listOf(stop(0, "MS", "Chennai Egmore", null, "21:30")), "22621")

    /** The example reply from the provider's documentation (the sample there is dated 2018), verbatim. Train 12565, journey start 15-09-2018. */
    val documentationSample = """{"ResponseCode":"200","StartDate":"15-09-2018","TrainNumber":"12565","CurrentPosition":null,"CurrentStation":{"SerialNo":"0","StationName":"Jagatbela","StationCode":"JTB","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"04:27PM","ActualArrival":"04:41PM","DelayInArrival":"14 M","ScheduleDeparture":"04:27PM","ActualDeparture":"04:41PM","DelayInDeparture":"14 M"},"TrainRoute":[{"SerialNo":"0","StationName":"Darbhanga","StationCode":"DBG","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"Source","ActualArrival":"Source","DelayInArrival":"-","ScheduleDeparture":"08:25AM","ActualDeparture":"08:25AM","DelayInDeparture":"00 M"},{"SerialNo":"1","StationName":"Samastipur","StationCode":"SPJ","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"09:15AM","ActualArrival":"09:15AM","DelayInArrival":"00 M","ScheduleDeparture":"09:35AM","ActualDeparture":"09:35AM","DelayInDeparture":"00 M"},{"SerialNo":"2","StationName":"Muzaffarpur","StationCode":"MFP","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"10:25AM","ActualArrival":"10:50AM","DelayInArrival":"25 M","ScheduleDeparture":"10:30AM","ActualDeparture":"10:55AM","DelayInDeparture":"25 M"},{"SerialNo":"3","StationName":"Hajipur","StationCode":"HJP","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"11:30AM","ActualArrival":"11:50AM","DelayInArrival":"20 M","ScheduleDeparture":"11:33AM","ActualDeparture":"11:53AM","DelayInDeparture":"20 M"},{"SerialNo":"4","StationName":"Sonpur","StationCode":"SEE","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"11:43AM","ActualArrival":"12:02PM","DelayInArrival":"19 M","ScheduleDeparture":"11:45AM","ActualDeparture":"12:02PM","DelayInDeparture":"17 M"},{"SerialNo":"5","StationName":"Chhapra","StationCode":"CPR","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"12:45PM","ActualArrival":"01:25PM","DelayInArrival":"40 M","ScheduleDeparture":"12:50PM","ActualDeparture":"01:30PM","DelayInDeparture":"40 M"},{"SerialNo":"6","StationName":"Siwan","StationCode":"SV","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"01:40PM","ActualArrival":"02:22PM","DelayInArrival":"42 M","ScheduleDeparture":"01:45PM","ActualDeparture":"02:27PM","DelayInDeparture":"42 M"},{"SerialNo":"7","StationName":"Gorakhpur","StationCode":"GKP","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"03:50PM","ActualArrival":"04:15PM","DelayInArrival":"25 M","ScheduleDeparture":"04:05PM","ActualDeparture":"04:24PM","DelayInDeparture":"19 M"},{"SerialNo":"8","StationName":"Lucknow","StationCode":"LKO","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"09:00PM","ActualArrival":"09:00PM","DelayInArrival":"00 M","ScheduleDeparture":"09:10PM","ActualDeparture":"09:10PM","DelayInDeparture":"00 M"},{"SerialNo":"9","StationName":"Kanpur Central","StationCode":"CNB","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"10:48PM","ActualArrival":"10:48PM","DelayInArrival":"00 M","ScheduleDeparture":"10:58PM","ActualDeparture":"10:58PM","DelayInDeparture":"00 M"},{"SerialNo":"10","StationName":"New Delhi","StationCode":"NDLS","Distance":"-","IsDeparted":"-","Day":"0","ScheduleArrival":"05:30AM","ActualArrival":"05:30AM","DelayInArrival":"00 M","ScheduleDeparture":"Destination","ActualDeparture":"Destination","DelayInDeparture":"-"}],"Message":"SUCCESS"}"""
}
