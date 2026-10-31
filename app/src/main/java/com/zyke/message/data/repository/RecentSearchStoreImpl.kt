package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.search.RecentSearchStore

/**
 * The last searches, in private preferences. Left out of backups (see PrefsBackup): what someone looked up is theirs
 * alone and should not travel to another phone with their settings.
 */
class RecentSearchStoreImpl(context: Context) : RecentSearchStore {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun load(): List<String> = runCatching {
        prefs.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    }.getOrDefault(emptyList())

    override fun save(searches: List<String>) {
        prefs.edit().putString(KEY, searches.joinToString(SEPARATOR.toString())).apply()
    }

    companion object {
        const val NAME = "recent_searches"
        private const val KEY = "queries"
        private const val SEPARATOR = '\n'
    }
}
