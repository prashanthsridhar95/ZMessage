package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.logo.LogoEntry
import com.zyke.message.domain.logo.LogoList
import com.zyke.message.domain.repository.LogoListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The person's own logo list in the private preferences file `logo_urls` (brand key to web address). Like the nicknames and
 * pictures it belongs to, it is part of the setup the encrypted backup carries; it holds no message text, number or amount.
 */
class LogoListRepositoryImpl(context: Context) : LogoListRepository {

    private val prefs = context.applicationContext.getSharedPreferences("logo_urls", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    override val entries: StateFlow<List<LogoEntry>> = _entries.asStateFlow()

    @Synchronized
    override fun record(name: String, url: String): Boolean {
        val key = LogoList.keyOf(name) ?: return false
        val clean = LogoList.cleanUrl(url) ?: return false
        val current = _entries.value
        if (current.none { it.key == key } && current.size >= LogoList.MAX_ENTRIES) return false
        prefs.edit().putString(key, clean).apply()
        _entries.value = LogoList.merge(current, listOf(LogoEntry(key, clean)))
        return true
    }

    @Synchronized
    override fun forget(name: String) {
        val key = LogoList.keyOf(name) ?: return
        if (_entries.value.none { it.key == key }) return
        prefs.edit().remove(key).apply()
        _entries.value = _entries.value.filterNot { it.key == key }
    }

    private fun load(): List<LogoEntry> =
        LogoList.merge(emptyList(), prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { LogoEntry(key, it) } })
}
