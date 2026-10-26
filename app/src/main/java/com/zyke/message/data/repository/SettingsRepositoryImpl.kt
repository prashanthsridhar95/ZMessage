package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(AppSettings(screenProtection = prefs.getBoolean(SCREEN_PROTECTION, false)))
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun setScreenProtection(enabled: Boolean) {
        prefs.edit().putBoolean(SCREEN_PROTECTION, enabled).apply()
        _settings.value = _settings.value.copy(screenProtection = enabled)
    }

    private companion object {
        const val SCREEN_PROTECTION = "screen_protection"
    }
}
