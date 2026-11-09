package com.zyke.message.presentation.trains

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

/**
 * The hook by which a train notification opens the journey's sheet. MainActivity puts the journey's key here from the incoming intent;
 * Home takes it and opens the sheet. The key is read, used to find a journey the app already knows and never logged.
 */
object TrainRequests {
    const val ACTION_OPEN = "com.zyke.message.action.OPEN_TRAIN"
    const val EXTRA_KEY = "com.zyke.message.extra.TRAIN_KEY"

    val pending = mutableStateOf<String?>(null)

    fun fromIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN) return
        pending.value = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.length in 3..40 }
    }
}

/**
 * "Add a trip" was chosen somewhere that is not a flight or train form (the entry sheet, the Home cards): Home asks which kind,
 * a flight or a train, and opens that form.
 */
object TripRequests {
    val chooseRequested = mutableStateOf(false)
}
