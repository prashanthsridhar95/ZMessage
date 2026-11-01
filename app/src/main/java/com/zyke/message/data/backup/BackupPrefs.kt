package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.domain.backup.BackupFrequency
import com.zyke.message.domain.backup.BackupSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What happened the last time a place was backed up to. */
data class BackupResult(val at: Instant, val sizeBytes: Long)

data class BackupFailureNote(val at: Instant, val failure: BackupFailure)

/** Everything the backup screen and scheduler need to know, in one immutable value. */
data class BackupView(
    val schedule: BackupSchedule = BackupSchedule(),
    /** Option A: keep the Auto Backup file on this phone fresh (Android uploads it by itself). */
    val localEnabled: Boolean = false,
    /** Option B: upload to the person's own Google Drive private app folder. Off until switched on. */
    val driveEnabled: Boolean = false,
    /** Option C: also write a dated file into a folder the person chose. Needs no sign-in and no network. */
    val folderEnabled: Boolean = false,
    /** Short readable name of that folder (for example "Download/Zyke"); empty when none is chosen. */
    val folderLabel: String = "",
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val keyReady: Boolean = false,
    val lastLocal: BackupResult? = null,
    val lastDrive: BackupResult? = null,
    val lastFolder: BackupResult? = null,
    val failure: Map<BackupMechanism, BackupFailureNote> = emptyMap(),
    val running: Boolean = false,
    val picturesSkipped: Boolean = false
) {
    fun result(m: BackupMechanism): BackupResult? = when (m) {
        BackupMechanism.LOCAL -> lastLocal
        BackupMechanism.DRIVE -> lastDrive
        BackupMechanism.FOLDER -> lastFolder
    }
    fun enabled(m: BackupMechanism): Boolean = when (m) {
        BackupMechanism.LOCAL -> localEnabled
        BackupMechanism.DRIVE -> driveEnabled
        BackupMechanism.FOLDER -> folderEnabled
    }
    fun lastSuccess(m: BackupMechanism): Instant? = result(m)?.at
    fun lastFailure(m: BackupMechanism): Instant? = failure[m]?.at
}

/**
 * The backup settings and the record of the last runs, in two preference files:
 * `backup_schedule` (the schedule and the switches; part of the setup backup, harmless) and `backup_state`
 * (the Drive switch, the last results, the first-run marker; never backed up). A third file, `backup_folder`, holds
 * the chosen folder's address and switch; it is never backed up either, because the access grant does not survive a
 * reinstall. No account name, token or PIN is stored in any of them.
 */
class BackupPrefs(context: Context, keyReady: () -> Boolean) {
    private val settings = context.applicationContext.getSharedPreferences("backup_schedule", Context.MODE_PRIVATE)
    private val state = context.applicationContext.getSharedPreferences("backup_state", Context.MODE_PRIVATE)
    private val folder = context.applicationContext.getSharedPreferences("backup_folder", Context.MODE_PRIVATE)
    private val keyReady = keyReady
    @Volatile private var running = false
    private val _view = MutableStateFlow(read())
    val view: StateFlow<BackupView> = _view.asStateFlow()

    private fun read(): BackupView {
        fun result(prefix: String): BackupResult? =
            state.getLong("${prefix}_at", 0L).takeIf { it > 0 }?.let { BackupResult(Instant.ofEpochMilli(it), state.getLong("${prefix}_size", 0L)) }
        fun failure(m: BackupMechanism): BackupFailureNote? {
            val at = state.getLong("fail_${m.name}_at", 0L)
            val kind = runCatching { BackupFailure.valueOf(state.getString("fail_${m.name}", "") ?: "") }.getOrNull()
            return if (at > 0 && kind != null) BackupFailureNote(Instant.ofEpochMilli(at), kind) else null
        }
        val schedule = runCatching {
            BackupSchedule(
                frequency = BackupFrequency.valueOf(settings.getString("frequency", BackupFrequency.DAILY.name) ?: BackupFrequency.DAILY.name),
                timeOfDay = LocalTime.of(settings.getInt("hour", 2).coerceIn(0, 23), settings.getInt("minute", 0).coerceIn(0, 59)),
                dayOfWeek = DayOfWeek.of(settings.getInt("day_of_week", DayOfWeek.SUNDAY.value).coerceIn(1, 7)),
                dayOfMonth = settings.getInt("day_of_month", 1).coerceIn(1, 31)
            )
        }.getOrDefault(BackupSchedule())
        return BackupView(
            schedule = schedule,
            localEnabled = settings.getBoolean("local", false),
            driveEnabled = state.getBoolean("drive", false),
            folderEnabled = folder.getBoolean("enabled", false) && !folder.getString("tree", null).isNullOrEmpty(),
            folderLabel = folder.getString("label", "") ?: "",
            wifiOnly = settings.getBoolean("wifi_only", true),
            chargingOnly = settings.getBoolean("charging_only", false),
            keyReady = keyReady(),
            lastLocal = result("local"),
            lastDrive = result("drive"),
            lastFolder = result("folder"),
            failure = BackupMechanism.values().mapNotNull { m -> failure(m)?.let { m to it } }.toMap(),
            running = running,
            picturesSkipped = state.getBoolean("pictures_skipped", false)
        )
    }

    /** Re-reads everything (after a restore, or after the key changed). */
    fun refresh() { _view.value = read() }

    fun setSchedule(schedule: BackupSchedule) {
        settings.edit()
            .putString("frequency", schedule.frequency.name)
            .putInt("hour", schedule.timeOfDay.hour).putInt("minute", schedule.timeOfDay.minute)
            .putInt("day_of_week", schedule.dayOfWeek.value).putInt("day_of_month", schedule.dayOfMonth)
            .apply()
        refresh()
    }

    fun setLocalEnabled(on: Boolean) { settings.edit().putBoolean("local", on).apply(); refresh() }
    fun setDriveEnabled(on: Boolean) { state.edit().putBoolean("drive", on).apply(); refresh() }

    /** The chosen folder's tree address, or null when none is chosen. */
    val folderTree: String? get() = folder.getString("tree", null)?.takeIf { it.isNotEmpty() }

    /** Remembers the folder the person picked and switches the folder backup on. */
    fun setFolder(tree: String, label: String) {
        folder.edit().putString("tree", tree).putString("label", label).putBoolean("enabled", true).apply()
        refresh()
    }

    fun setFolderLabel(label: String) { folder.edit().putString("label", label).apply(); refresh() }

    /** Switches the folder backup off and forgets the folder (the caller gives the access grant back). */
    fun clearFolder() {
        folder.edit().clear().apply()
        refresh()
    }

    fun setWifiOnly(on: Boolean) { settings.edit().putBoolean("wifi_only", on).apply(); refresh() }
    fun setChargingOnly(on: Boolean) { settings.edit().putBoolean("charging_only", on).apply(); refresh() }

    /** True once Google has been signed in to (so a later "not allowed" means the sign-in ran out, not that it never happened). */
    var driveConsented: Boolean
        get() = state.getBoolean("drive_consented", false)
        set(value) { state.edit().putBoolean("drive_consented", value).apply() }

    /** True when the last backup left the sender pictures out because they were too big together. */
    var picturesSkipped: Boolean
        get() = state.getBoolean("pictures_skipped", false)
        set(value) { state.edit().putBoolean("pictures_skipped", value).apply(); refresh() }

    /** Failed tries in a row for the job system's quick retries; reset after a success or when giving up for now. */
    var attempts: Int
        get() = state.getInt("attempts", 0)
        set(value) { state.edit().putInt("attempts", value).apply() }

    fun setRunning(value: Boolean) { running = value; refresh() }

    fun recordSuccess(m: BackupMechanism, at: Instant, sizeBytes: Long) {
        val prefix = m.name.lowercase()
        state.edit().putLong("${prefix}_at", at.toEpochMilli()).putLong("${prefix}_size", sizeBytes)
            .remove("fail_${m.name}").remove("fail_${m.name}_at").apply()
        refresh()
    }

    fun recordFailure(m: BackupMechanism, at: Instant, failure: BackupFailure) {
        state.edit().putString("fail_${m.name}", failure.name).putLong("fail_${m.name}_at", at.toEpochMilli()).apply()
        refresh()
    }

    fun clearFailure(m: BackupMechanism) {
        state.edit().remove("fail_${m.name}").remove("fail_${m.name}_at").apply()
        refresh()
    }

    /** Set once the first-launch "Welcome" step has been answered, or the install already had a setup. Never backed up. */
    var welcomeDone: Boolean
        get() = state.getBoolean("welcome_done", false)
        set(value) { state.edit().putBoolean("welcome_done", value).apply() }

    companion object {
        /**
         * True on a fresh install with nothing set up yet, until the Welcome step has been answered. Run once, first
         * thing at start, before anything else has had a chance to write. An install that already holds a setup (an
         * update from an older version) is marked as done without being asked.
         */
        fun needsWelcome(context: Context): Boolean = FirstRun.decideWelcome(context)
    }
}
