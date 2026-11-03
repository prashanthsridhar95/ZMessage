package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.NicknameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nicknames for senders / receivers, kept in private preferences. */
class NicknameRepositoryImpl(context: Context) : NicknameRepository {

    private val prefs = context.applicationContext.getSharedPreferences("nicknames", Context.MODE_PRIVATE)

    private val _nicknames = MutableStateFlow(load())
    override val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    @Synchronized
    override fun set(key: String, name: String?) {
        val clean = name?.trim().orEmpty()
        val editor = prefs.edit()
        if (clean.isEmpty()) editor.remove(key) else editor.putString(key, clean)
        editor.apply()
        val before = _nicknames.value.size
        _nicknames.value = if (clean.isEmpty()) _nicknames.value - key else _nicknames.value + (key to clean)
        com.zyke.message.data.diag.DevLog.d("nickname") {
            "set key=${com.zyke.message.domain.diag.DiagLog.key(key)} nameLength=${clean.length} cleared=${clean.isEmpty()} count $before -> ${_nicknames.value.size} storedNow=${prefs.contains(key)}"
        }
    }

    override fun setAll(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        synchronized(this) {
            val editor = prefs.edit()
            var next = _nicknames.value
            for ((key, name) in changes) {
                val clean = name?.trim().orEmpty()
                if (clean.isEmpty()) { editor.remove(key); next = next - key } else { editor.putString(key, clean); next = next + (key to clean) }
            }
            editor.apply()
            _nicknames.value = next
            com.zyke.message.data.diag.DevLog.d("nickname") { "set ${changes.size} keys at once -> ${next.size} names" }
        }
    }

    private fun load(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}
