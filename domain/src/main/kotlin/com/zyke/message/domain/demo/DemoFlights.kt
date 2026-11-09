package com.zyke.message.domain.demo

import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightStatusKind
import com.zyke.message.domain.flight.FlightTracker
import com.zyke.message.domain.flight.LiveStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Demo mode's flight: a made-up live status worked out from the clock, so the card and the sheet can be shown without the
 * network. The flight is 25 minutes late; it then departs, flies and lands as the schedule passes. "Updated" is the moment
 * it is asked, so it changes on every refresh.
 */
object DemoFlights {
    const val DELAY_MINUTES = 25

    fun live(flight: BookedFlight, nowMillis: Long, zone: ZoneId): LiveStatus {
        val now = Instant.ofEpochMilli(nowMillis)
        val planned = FlightTracker.scheduledDeparture(flight, zone) ?: now
        val plannedArrival = FlightTracker.scheduledArrival(flight, zone) ?: planned.plus(Duration.ofHours(2))
        val late = Duration.ofMinutes(DELAY_MINUTES.toLong())
        val estimatedDeparture = planned.plus(late)
        val estimatedArrival = plannedArrival.plus(late)
        val kind = when {
            !now.isBefore(estimatedArrival) -> FlightStatusKind.LANDED
            !now.isBefore(estimatedDeparture) -> FlightStatusKind.IN_AIR
            else -> FlightStatusKind.DELAYED
        }
        return LiveStatus(
            kind = kind,
            scheduledDeparture = planned, estimatedDeparture = estimatedDeparture.takeIf { kind == FlightStatusKind.DELAYED },
            actualDeparture = estimatedDeparture.takeIf { kind != FlightStatusKind.DELAYED },
            scheduledArrival = plannedArrival, estimatedArrival = estimatedArrival.takeIf { kind != FlightStatusKind.LANDED },
            actualArrival = estimatedArrival.takeIf { kind == FlightStatusKind.LANDED },
            originCode = flight.origin?.code, destinationCode = flight.destination?.code,
            terminal = "1", gate = "B7", arrivalTerminal = "3", baggage = "7",
            delayMinutes = DELAY_MINUTES, updatedAt = nowMillis
        )
    }
}
