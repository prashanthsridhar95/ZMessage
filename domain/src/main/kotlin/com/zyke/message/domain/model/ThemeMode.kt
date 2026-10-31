package com.zyke.message.domain.model

enum class ThemeMode(val label: String) {
    SYSTEM("System default"), LIGHT("Day"), DARK("Night"), BLACK("True black");

    companion object {
        /** The mode stored under [name]; anything missing or unknown (an older or newer build) is [SYSTEM]. */
        fun fromStored(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
