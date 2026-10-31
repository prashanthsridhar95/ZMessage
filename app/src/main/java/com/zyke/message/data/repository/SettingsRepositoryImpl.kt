package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppSettings(
            screenProtection = prefs.getBoolean(SCREEN_PROTECTION, false),
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString(THEME_MODE, null) } ?: ThemeMode.SYSTEM,
            fetchLogos = prefs.getBoolean(FETCH_LOGOS, true),
            appLock = prefs.getBoolean(APP_LOCK, false),
            morningDigest = prefs.getBoolean(MORNING_DIGEST, false),
            liveAlerts = prefs.getBoolean(LIVE_ALERTS, false),
            readingPaused = prefs.getBoolean(READING_PAUSED, false),
            weeklySummary = prefs.getBoolean(WEEKLY_SUMMARY, false),
            headsUp = prefs.getBoolean(HEADS_UP, false),
            lowBalanceFloor = prefs.getString(LOW_BALANCE_FLOOR, null)?.toBigDecimalOrNull()
        )
    )
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun setScreenProtection(enabled: Boolean) {
        prefs.edit().putBoolean(SCREEN_PROTECTION, enabled).apply()
        _settings.value = _settings.value.copy(screenProtection = enabled)
    }

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    override fun setFetchLogos(enabled: Boolean) {
        prefs.edit().putBoolean(FETCH_LOGOS, enabled).apply()
        _settings.value = _settings.value.copy(fetchLogos = enabled)
    }

    override fun setAppLock(enabled: Boolean) {
        prefs.edit().putBoolean(APP_LOCK, enabled).apply()
        _settings.value = _settings.value.copy(appLock = enabled)
    }

    override fun setMorningDigest(enabled: Boolean) {
        prefs.edit().putBoolean(MORNING_DIGEST, enabled).apply()
        _settings.value = _settings.value.copy(morningDigest = enabled)
    }

    override fun setLiveAlerts(enabled: Boolean) {
        prefs.edit().putBoolean(LIVE_ALERTS, enabled).apply()
        _settings.value = _settings.value.copy(liveAlerts = enabled)
    }

    override fun setReadingPaused(paused: Boolean) {
        prefs.edit().putBoolean(READING_PAUSED, paused).apply()
        _settings.value = _settings.value.copy(readingPaused = paused)
    }

    override fun setWeeklySummary(enabled: Boolean) {
        prefs.edit().putBoolean(WEEKLY_SUMMARY, enabled).apply()
        _settings.value = _settings.value.copy(weeklySummary = enabled)
    }

    override fun setHeadsUp(enabled: Boolean) {
        prefs.edit().putBoolean(HEADS_UP, enabled).apply()
        _settings.value = _settings.value.copy(headsUp = enabled)
    }

    override fun setLowBalanceFloor(floor: java.math.BigDecimal?) {
        prefs.edit().apply { if (floor == null) remove(LOW_BALANCE_FLOOR) else putString(LOW_BALANCE_FLOOR, floor.toPlainString()) }.apply()
        _settings.value = _settings.value.copy(lowBalanceFloor = floor)
    }

    private companion object {
        const val WEEKLY_SUMMARY = "weekly_summary"
        const val HEADS_UP = "heads_up"
        const val LOW_BALANCE_FLOOR = "low_balance_floor"
        const val READING_PAUSED = "reading_paused"
        const val LIVE_ALERTS = "live_alerts"
        const val FETCH_LOGOS = "fetch_logos"
        const val SCREEN_PROTECTION = "screen_protection"
        const val THEME_MODE = "theme_mode"
        const val APP_LOCK = "app_lock"
        const val MORNING_DIGEST = "morning_digest"
    }
}
