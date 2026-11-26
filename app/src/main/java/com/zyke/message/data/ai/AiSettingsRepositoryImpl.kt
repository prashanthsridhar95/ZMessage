package com.zyke.message.data.ai

import android.content.Context
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiSettings
import com.zyke.message.domain.ai.AiSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The on-device AI switches, in private preferences (`ai_settings`). Everything is off until the person turns it on. The file
 * describes this phone (whether its model is there), so a backup never carries it and a restored phone never starts using the
 * AI by itself (see PrefsBackup). In Demo mode the context's preferences are in memory, and the hub does not use this class.
 */
class AiSettingsRepositoryImpl(context: Context) : AiSettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    override val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private fun read(): AiSettings {
        val names = prefs.getStringSet(KEY_FEATURES, null).orEmpty()
        return AiSettings(
            master = prefs.getBoolean(KEY_MASTER, false),
            explained = prefs.getBoolean(KEY_EXPLAINED, false),
            enabled = AiFeature.entries.filter { it.name in names }.toSet(),
            batchOnlyWhileCharging = prefs.getBoolean(KEY_CHARGING, true)
        )
    }

    @Synchronized
    override fun update(change: (AiSettings) -> AiSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit()
            .putBoolean(KEY_MASTER, next.master)
            .putBoolean(KEY_EXPLAINED, next.explained)
            .putStringSet(KEY_FEATURES, next.enabled.map { it.name }.toSet())
            .putBoolean(KEY_CHARGING, next.batchOnlyWhileCharging)
            .apply()
    }

    companion object {
        const val FILE = "ai_settings"
        private const val KEY_MASTER = "master"
        private const val KEY_EXPLAINED = "explained"
        private const val KEY_FEATURES = "features"
        private const val KEY_CHARGING = "batch_charging_only"
    }
}
