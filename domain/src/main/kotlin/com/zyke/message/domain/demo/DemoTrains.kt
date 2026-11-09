package com.zyke.message.domain.demo

import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.TrainLive
import com.zyke.message.domain.train.TrainStop
import com.zyke.message.domain.train.TrainTracker
import java.time.Duration
import java.time.Instant

/**
 * Demo mode's train: a made-up live status worked out from the clock, so the card and the sheet can be shown without the network.
 * The train runs [DELAY_MINUTES] late along a fixed made-up route and arrives as the schedule passes. "Updated" is the moment it is asked.
 */
object DemoTrains {
    const val DELAY_MINUTES = 12

    /** Stops as (code, name, minutes after the first departure). The last is the destination. */
    private val ROUTE = listOf(
        Triple("MS", "Chennai Egmore", 0), Triple("TBM", "Tambaram", 30), Triple("CGL", "Chengalpattu", 55), Triple("VM", "Villupuram", 130),
        Triple("VRI", "Vriddhachalam", 195), Triple("ALU", "Ariyalur", 245), Triple("TPJ", "Tiruchirappalli", 300)
    )

    fun live(train: BookedTrain, nowMillis: Long): TrainLive {
        val now = Instant.ofEpochMilli(nowMillis)
        val first = TrainTracker.scheduledDeparture(train) ?: now
        val late = Duration.ofMinutes(DELAY_MINUTES.toLong())
        val halt = Duration.ofMinutes(2)
        fun at(offset: Int) = first.plus(Duration.ofMinutes(offset.toLong()))
        val stops = ROUTE.mapIndexed { i, (code, name, offset) ->
            val arrives = at(offset)
            val leaves = when (i) { ROUTE.lastIndex -> null; 0 -> arrives; else -> arrives.plus(halt) }
            val expArr = arrives.plus(late)
            val expDep = leaves?.plus(late)
            TrainStop(
                code = code, name = name, day = 1,
                scheduledArrival = if (i == 0) null else arrives, scheduledDeparture = leaves,
                expectedArrival = if (i == 0) null else expArr, expectedDeparture = expDep,
                actualArrival = expArr.takeIf { i > 0 && !now.isBefore(it) },
                actualDeparture = expDep?.takeIf { !now.isBefore(it) },
                platform = when (i) { 0 -> "4"; ROUTE.lastIndex -> "2"; else -> null }
            )
        }
        val reached = stops.indexOfLast { it.actualArrival != null || it.actualDeparture != null }
        val position = stops.getOrNull(reached)
        val atStation = position != null && position.actualDeparture == null && reached != stops.lastIndex
        val done = stops.last().actualArrival != null
        return TrainLive(
            trainName = "Kanniyakumari Express", stops = stops,
            lastStationCode = position?.code, lastStationName = position?.name, lastSeenAt = position?.let { it.actualDeparture ?: it.actualArrival },
            delayMinutes = DELAY_MINUTES, atStation = atStation, started = stops.first().actualDeparture != null, terminated = done,
            boardingDay = 1, updatedAt = nowMillis
        )
    }
}
