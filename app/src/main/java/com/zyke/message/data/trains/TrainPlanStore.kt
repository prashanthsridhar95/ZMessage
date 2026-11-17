package com.zyke.message.data.trains

import android.content.Context
import com.zyke.message.data.trips.TripPlanStore
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.TrainPlanCodec

/**
 * The journeys found in the person's texts as the alarm, the boot receiver and the tracking service know them when the app is not open
 * (without the PNR; see [TrainPlanCodec]), with the stops and reminders: see [TripPlanStore]. The file `train_plan` is left out of backups.
 */
class TrainPlanStore(context: Context) : TripPlanStore<BookedTrain>(
    context, "train_plan", "trains", { TrainPlanCodec.encode(it) }, { TrainPlanCodec.decode(it) }
)
