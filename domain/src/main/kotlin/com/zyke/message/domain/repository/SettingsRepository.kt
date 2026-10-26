package com.zyke.message.domain.repository

import com.zyke.message.domain.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setScreenProtection(enabled: Boolean)
}
