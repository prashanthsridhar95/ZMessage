package com.zyke.message.notifications

import com.zyke.message.domain.flight.TrackDecision

/**
 * The flight's live notification service. A foreground service of type dataSync, declared in the manifest as before; the loop,
 * the notifications and the stop rules are in [TripTrackingService]. It asks only through FlightService (the one pinned fetcher).
 * Nothing here writes a flight number, route or status to the log.
 */
class FlightTrackingService : TripTrackingService<TrackDecision>() {
    override val control: TripTrackingControl<*, TrackDecision> get() = FlightTrackingControl

    companion object {
        const val ACTION_STOP = "com.zyke.message.action.STOP_FLIGHT_TRACKING"
        const val EXTRA_KEY = "com.zyke.message.extra.FLIGHT_KEY"
    }
}
