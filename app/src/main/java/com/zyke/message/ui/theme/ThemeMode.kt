package com.zyke.message.ui.theme

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("System default"), LIGHT("Day"), DARK("Night");

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "theme_mode"

        fun load(context: Context): ThemeMode {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: SYSTEM
        }

        fun save(context: Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name).apply()
        }
    }
}
