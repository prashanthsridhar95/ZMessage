package com.zyke.message.data.flights

import android.content.Context
import com.zyke.message.data.trips.TripPlanStore
import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightPlanCodec

/**
 * The flights found in the person's texts as the alarm, the boot receiver and the tracking service know them when the app is not open
 * (without the PNR; see [FlightPlanCodec]), with the stops and reminders: see [TripPlanStore]. The file `flight_plan` is left out of backups.
 */
class FlightPlanStore(context: Context) : TripPlanStore<BookedFlight>(
    context, "flight_plan", "flights", { FlightPlanCodec.encode(it) }, { FlightPlanCodec.decode(it) }
)
