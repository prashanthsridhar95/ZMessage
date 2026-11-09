package com.zyke.message.data.trains

import android.content.Context
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.TrainPlanCodec

/**
 * What the alarm, the boot receiver and the tracking service know about the journeys found in the person's texts when the app is not
 * open: the journeys themselves (without the PNR; see [TrainPlanCodec]), which ones the person pressed "Stop tracking" on, and which have
 * had their reminder. Bookkeeping only: the file is left out of backups and rewritten whenever Home is shown.
 */
class TrainPlanStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("train_plan", Context.MODE_PRIVATE)

    fun fromTexts(): List<BookedTrain> = runCatching { TrainPlanCodec.decode(prefs.getString(TRAINS, "").orEmpty()) }.getOrDefault(emptyList())

    fun saveFromTexts(trains: List<BookedTrain>) {
        val text = TrainPlanCodec.encode(trains)
        if (text != prefs.getString(TRAINS, "")) prefs.edit().putString(TRAINS, text).apply()
    }

    /** Keys of journeys the person stopped tracking. The day is in the key, so a stop never hides another day's journey. */
    fun stopped(): Set<String> = prefs.getStringSet(STOPPED, emptySet()).orEmpty().toSet()

    fun stop(key: String) {
        prefs.edit().putStringSet(STOPPED, (stopped() + key).toList().takeLast(KEEP).toSet()).apply()
    }

    fun resume(key: String) {
        if (key in stopped()) prefs.edit().putStringSet(STOPPED, stopped() - key).apply()
    }

    /** True the first time it is asked for a journey, so a reminder is posted once. */
    fun markReminded(key: String): Boolean {
        val done = prefs.getStringSet(REMINDED, emptySet()).orEmpty()
        if (key in done) return false
        prefs.edit().putStringSet(REMINDED, (done + key).toList().takeLast(KEEP).toSet()).apply()
        return true
    }

    private companion object {
        const val TRAINS = "trains"
        const val STOPPED = "stopped"
        const val REMINDED = "reminded"
        const val KEEP = 40
    }
}
