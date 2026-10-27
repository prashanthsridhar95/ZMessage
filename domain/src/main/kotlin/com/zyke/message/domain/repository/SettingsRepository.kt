package com.zyke.message.domain.repository

import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setScreenProtection(enabled: Boolean)

    fun setThemeMode(mode: ThemeMode)

    fun setFetchLogos(enabled: Boolean)

    fun setAppLock(enabled: Boolean)

    fun setMorningDigest(enabled: Boolean)
}
