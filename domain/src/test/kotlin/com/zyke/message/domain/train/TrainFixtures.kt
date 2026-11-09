package com.zyke.message.domain.train

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Sample texts and replies for the train tests. NOT CAPTURED: the texts are invented in the wording IRCTC, Indian Railways and the
 * booking apps use (every number, PNR and name is made up), and the replies are HAND-BUILT in the shape [TrainStatusParser] expects. The
 * build environment could not reach any live running-status service, so nothing here is verified against one. Replace the replies with a
 * captured one when a device with internet is at hand.
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

    private fun stop(code: String, name: String, day: Int, sa: String?, sd: String?, extra: String = ""): String =
        """{"station_code":"$code","station_name":"$name","day":$day,"sch_arr":${sa?.let { "\"$it\"" } ?: "\"--\""},"sch_dep":${sd?.let { "\"$it\"" } ?: "\"--\""}$extra}"""

    /** Running: left MS 12 minutes late, now between Villupuram and Vriddhachalam. Fetched 23:50 on the 12th (IST). */
    val running = """{"success":true,"train_number":"12633","train_name":"KANYAKUMARI EXP","status":"Running","delay":12,"current_station_code":"VM","at_station":false,
"route":[
${stop("MS", "Chennai Egmore", 1, null, "21:30", ""","act_dep":"21:42","has_arrived":true,"has_departed":true,"platform":"4","delay_dep":12""")},
${stop("TBM", "Tambaram", 1, "22:00", "22:02", ""","act_arr":"22:12","act_dep":"22:14","has_arrived":true,"has_departed":true,"delay_dep":12""")},
${stop("CGL", "Chengalpattu", 1, "22:25", "22:27", ""","act_arr":"22:37","act_dep":"22:39","has_arrived":true,"has_departed":true""")},
${stop("VM", "Villupuram Jn", 1, "23:40", "23:42", ""","act_arr":"23:52","act_dep":"23:54","has_arrived":true,"has_departed":true""")},
${stop("VRI", "Vriddhachalam Jn", 2, "00:45", "00:47", ""","exp_arr":"00:57","exp_dep":"00:59","has_arrived":false,"has_departed":false""")},
${stop("ALU", "Ariyalur", 2, "01:35", "01:37")},
${stop("TPJ", "Tiruchirappalli Jn", 2, "04:35", null, ""","platform":"2"""")}
]}"""

    val notStarted = """{"success":true,"train_number":"12633","train_name":"KANYAKUMARI EXP","status":"Not started yet","delay":0,
"route":[${stop("MS", "Chennai Egmore", 1, null, "21:30")},${stop("TPJ", "Tiruchirappalli Jn", 2, "04:35", null)}]}"""

    val cancelled = """{"success":true,"train_number":"12633","train_name":"KANYAKUMARI EXP","status":"Train cancelled","delay":0,
"route":[${stop("MS", "Chennai Egmore", 1, null, "21:30")},${stop("TPJ", "Tiruchirappalli Jn", 2, "04:35", null)}]}"""

    /** Arrived at Tiruchirappalli at 04:41 on the 13th, 6 minutes late. */
    val arrived = """{"success":true,"train_number":"12633","train_name":"KANYAKUMARI EXP","status":"Arrived at destination","delay":6,"current_station_code":"TPJ",
"route":[
${stop("MS", "Chennai Egmore", 1, null, "21:30", ""","act_dep":"21:36","has_departed":true,"has_arrived":true""")},
${stop("VM", "Villupuram Jn", 1, "23:40", "23:42", ""","act_arr":"23:48","act_dep":"23:50","has_arrived":true,"has_departed":true""")},
${stop("TPJ", "Tiruchirappalli Jn", 2, "04:35", null, ""","act_arr":"04:41","has_arrived":true,"platform":"2"""")}
]}"""

    val noRoute = """{"success":false,"message":"No running status for this train today"}"""
    val otherTrain = """{"success":true,"train_number":"22621","route":[${stop("MS", "Chennai Egmore", 1, null, "21:30")}]}"""
}
