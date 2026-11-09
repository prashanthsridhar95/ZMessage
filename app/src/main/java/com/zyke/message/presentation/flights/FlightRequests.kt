package com.zyke.message.presentation.flights

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

/**
 * The hook by which a flight notification opens the flight's sheet. MainActivity puts the flight's key here from the incoming
 * intent; Home takes it and opens the sheet. The key is read, used to find a flight the app already knows and never logged.
 */
object FlightRequests {
    const val ACTION_OPEN = "com.zyke.message.action.OPEN_FLIGHT"
    const val EXTRA_KEY = "com.zyke.message.extra.FLIGHT_KEY"

    val pending = mutableStateOf<String?>(null)

    /** "Add a flight" was chosen somewhere that is not the Home flight card (the entry sheet); Home opens the form. */
    val addRequested = mutableStateOf(false)

    fun fromIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN) return
        pending.value = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.length in 3..40 }
    }
}
