package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.backup.FirstRunDecision
import java.io.File

/**
 * The first-launch decision and its safety net. Everything here is wrapped so that no step can stop the app from
 * opening: when anything goes wrong the answer is "no Welcome" (or, in the Welcome screen, "Start fresh" only), and
 * the failure is written to the developer log under the tag "firstrun", even though a fresh install has logging off.
 *
 * The decision itself is [FirstRunDecision] (pure, tested). This object only gathers the facts from the phone.
 */
object FirstRun {
    @PublishedApi internal const val TAG = "firstrun"
    private const val STATE = "backup_state"
    private const val DONE = "welcome_done"
    private const val SHOWS = "welcome_shows"

    /**
     * Run once, first thing at start, before any store has written. True when the Welcome step should be shown.
     * Welcome counts how often it was shown without an answer; after [FirstRunDecision.MAX_SHOWS] it stops, so a
     * problem in it (a crash while it is on screen) can never repeat forever.
     */
    fun decideWelcome(context: Context): Boolean = try {
        val app = context.applicationContext ?: context
        val state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val done = state.getBoolean(DONE, false)
        val shows = state.getInt(SHOWS, 0)
        val setupFiles = !done && PrefsBackup.hasSetupData(app)
        val leftover = !done && runCatching { File(File(app.filesDir, BackupEngine.DIR), BackupEngine.NAME).isFile }.getOrDefault(false)
        when (val action = FirstRunDecision.decide(setupFiles, done, leftover, shows)) {
            FirstRunDecision.Action.SKIP -> false
            FirstRunDecision.Action.SKIP_AND_MARK_DONE -> {
                state.edit().putBoolean(DONE, true).apply()
                DevLog.always(TAG, "no welcome: setup=$setupFiles shows=$shows -> marked done")
                false
            }
            FirstRunDecision.Action.WELCOME -> {
                state.edit().putInt(SHOWS, shows + 1).apply()
                DevLog.always(TAG, "welcome will be shown (show ${shows + 1}): leftoverBackupFile=$leftover")
                true
            }
        }
    } catch (e: Exception) {
        DevLog.always(TAG, "deciding about the welcome step failed; going on without it", e)
        false
    }

    /** The Welcome step has been answered (or must be given up on): never shown again. Never throws. */
    fun markDone(context: Context, why: String) {
        try {
            context.applicationContext.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
            DevLog.always(TAG, "welcome done: $why")
        } catch (e: Exception) {
            DevLog.always(TAG, "could not remember that the welcome step is done", e)
        }
    }

    /** Runs one first-launch step; a failure is logged and [fallback] returned instead of crashing the app. */
    inline fun <T> step(name: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        DevLog.always(TAG, "step '$name' failed", e)
        fallback
    }
}
