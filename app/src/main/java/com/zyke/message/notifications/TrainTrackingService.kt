package com.zyke.message.notifications

import com.zyke.message.domain.train.TrainDecision

/**
 * The train's live notification service: the twin of [FlightTrackingService], a foreground service of type dataSync declared in the
 * manifest as before; the loop, the notifications and the stop rules are in [TripTrackingService]. It asks only through TrainService
 * (the one pinned fetcher). Nothing here writes a train number, route or status to the log.
 */
class TrainTrackingService : TripTrackingService<TrainDecision>() {
    override val control: TripTrackingControl<*, TrainDecision> get() = TrainTrackingControl

    companion object {
        const val ACTION_STOP = "com.zyke.message.action.STOP_TRAIN_TRACKING"
        const val EXTRA_KEY = "com.zyke.message.extra.TRAIN_KEY"
    }
}
