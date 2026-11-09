package com.zyke.message.data.trains

import android.content.Context
import com.zyke.message.domain.train.Stations
import com.zyke.message.domain.train.TrainApi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's own train data key and the count of requests made today, in two preference files that describe this phone and are
 * left out of every backup (see `PrefsBackup`, `backup_rules.xml` and `data_extraction_rules.xml`; the same way the UAN of "Check
 * balance by SMS" is kept):
 *  - `train_key_device` holds the key, nothing else;
 *  - `train_usage_device` holds the day (yyyymmdd, Indian time) and how many requests were made that day. It never holds the key.
 *
 * Nothing here logs. The key is handed out only to [TrainSettings] and the service; the screens get "is there one" and a masked form.
 */
internal class TrainKeyStore(context: Context, private val today: () -> LocalDate = { LocalDate.now(Stations.ZONE) }) {
    private val keyPrefs = context.applicationContext.getSharedPreferences("train_key_device", Context.MODE_PRIVATE)
    private val usagePrefs = context.applicationContext.getSharedPreferences("train_usage_device", Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var key: String = TrainApi.cleanKey(keyPrefs.getString(KEY, "").orEmpty()).takeIf { TrainApi.isValidKey(it) }.orEmpty()
    private val _hasKey = MutableStateFlow(key.isNotEmpty())
    val hasKey: StateFlow<Boolean> = _hasKey

    private val _requests = MutableStateFlow(readCount())
    /** Requests made today (Indian time). */
    val requestsToday: StateFlow<Int> = _requests

    /** The key, or null when none is saved. */
    fun get(): String? = key.takeIf { it.isNotEmpty() }

    /** "••••1a2b": the last four characters of a long key, only dots for a short one. Empty when there is none. */
    fun masked(): String {
        val k = key
        if (k.isEmpty()) return ""
        return if (k.length >= MASK_MIN_LENGTH) "••••" + k.takeLast(4) else "••••"
    }

    /** Saves [raw] if it can be a key; false (and nothing changes) if not. */
    fun save(raw: String): Boolean {
        if (!TrainApi.isValidKey(raw)) return false
        synchronized(lock) {
            val clean = TrainApi.cleanKey(raw)
            keyPrefs.edit().putString(KEY, clean).apply()
            key = clean
            _hasKey.value = true
        }
        return true
    }

    fun clear() {
        synchronized(lock) {
            keyPrefs.edit().remove(KEY).apply()
            key = ""
            _hasKey.value = false
        }
    }

    /** Counts one request sent today. */
    fun recordRequest() {
        synchronized(lock) {
            val day = dayKey()
            val count = if (usagePrefs.getString(DAY, null) == day) usagePrefs.getInt(COUNT, 0) else 0
            usagePrefs.edit().putString(DAY, day).putInt(COUNT, count + 1).apply()
            _requests.value = count + 1
        }
    }

    /** Re-reads the count, so a new day starts again from zero even if nothing was asked yet. */
    fun syncDay() {
        synchronized(lock) { _requests.value = readCount() }
    }

    private fun readCount(): Int = if (usagePrefs.getString(DAY, null) == dayKey()) usagePrefs.getInt(COUNT, 0) else 0

    private fun dayKey(): String = today().format(DAY_FORMAT)

    private companion object {
        const val KEY = "api_key"
        const val DAY = "day"
        const val COUNT = "count"
        const val MASK_MIN_LENGTH = 8
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
