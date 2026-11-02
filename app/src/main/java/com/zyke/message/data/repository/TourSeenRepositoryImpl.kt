package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.data.backup.PrefsBackup
import com.zyke.message.domain.tour.TourInstallKind
import com.zyke.message.domain.tour.TourPolicy
import com.zyke.message.domain.tour.TourSeenRepository
import com.zyke.message.domain.tour.TourState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the tour stands, in a private preferences file that stays on this phone. It is left out of backups on purpose
 * (see PrefsBackup): restoring onto a new phone must not replay the tour.
 */
class TourSeenRepositoryImpl(context: Context) : TourSeenRepository {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(read(prefs))
    override val state: StateFlow<TourState> = flow

    @Synchronized
    override fun update(transform: (TourState) -> TourState) {
        val next = transform(flow.value)
        if (next == flow.value) return
        flow.value = next
        runCatching { write(prefs, next) }
    }

    companion object {
        const val NAME = "tour_state"
        private const val KIND = "kind"
        private const val OFFERED = "offered"
        private const val COMPLETED = "completed_version"
        private const val BANNER = "banner_dismissed_version"
        private const val RUNNING = "running"
        private const val INDEX = "saved_index"
        private const val AUTO = "auto_play"
        private const val ATTEMPTS = "resume_attempts"

        private fun read(p: SharedPreferences): TourState = runCatching {
            TourState(
                installKind = p.getString(KIND, null)?.let { name -> TourInstallKind.entries.firstOrNull { it.name == name } },
                offered = p.getBoolean(OFFERED, false),
                completedVersion = p.getInt(COMPLETED, 0),
                bannerDismissedVersion = p.getInt(BANNER, 0),
                running = p.getBoolean(RUNNING, false),
                savedIndex = p.getInt(INDEX, 0),
                autoPlay = p.getBoolean(AUTO, false),
                resumeAttempts = p.getInt(ATTEMPTS, 0)
            )
        }.getOrDefault(TourState())

        private fun write(p: SharedPreferences, s: TourState) {
            p.edit().apply {
                if (s.installKind != null) putString(KIND, s.installKind!!.name) else remove(KIND)
                putBoolean(OFFERED, s.offered)
                putInt(COMPLETED, s.completedVersion)
                putInt(BANNER, s.bannerDismissedVersion)
                putBoolean(RUNNING, s.running)
                putInt(INDEX, s.savedIndex)
                putBoolean(AUTO, s.autoPlay)
                putInt(ATTEMPTS, s.resumeAttempts)
            }.apply()
        }

        /**
         * Run once, first thing at start (before any store has written a setup): works out whether this is a fresh install or
         * an update, and remembers it. A later start finds the answer already there and does nothing, so the answer cannot
         * change as the person sets the app up. Never throws.
         */
        fun decideKindNow(context: Context) {
            val app = context.applicationContext ?: context
            val p = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            if (p.contains(KIND)) return
            val hasSetup = runCatching { PrefsBackup.hasSetupData(app) }.getOrDefault(true)
            val gap = runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                info.lastUpdateTime - info.firstInstallTime
            }.getOrDefault(Long.MAX_VALUE)
            val kind = TourPolicy.classify(hasSetup, gap)
            p.edit().putString(KIND, kind.name).apply()
        }

        /** After a restore: this phone's person is not new here. Written straight to the file (the process closes right after). */
        fun markRestored(context: Context) {
            runCatching {
                val p = (context.applicationContext ?: context).getSharedPreferences(NAME, Context.MODE_PRIVATE)
                write(p, read(p).restored())
            }
        }
    }
}
