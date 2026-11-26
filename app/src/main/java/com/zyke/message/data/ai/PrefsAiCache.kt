package com.zyke.message.data.ai

import android.content.Context
import com.zyke.message.domain.ai.AiCache

/**
 * What the AI suggested before, in private preferences (`ai_cache`), so the model is asked once per message. Keys are hashes,
 * values are answers the guard accepted (derived from message text), so this file is private to the app and is left out of every
 * backup and export (PrefsBackup.EXCLUDED, AiPolicyTest). Each value is written as `<sequence>|<text>` so the oldest go first.
 */
class PrefsAiCache(context: Context, private val max: Int = MAX_ENTRIES) : AiCache {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val entries = LinkedHashMap<String, String>()
    private var sequence = 0L

    init {
        prefs.all.entries
            .mapNotNull { (k, v) -> parse(k, v) }
            .sortedBy { it.second }
            .forEach { (k, seq, text) -> entries[k] = text; sequence = maxOf(sequence, seq) }
    }

    private fun parse(key: String, value: Any?): Triple<String, Long, String>? {
        val raw = value as? String ?: return null
        val seq = raw.substringBefore('|').toLongOrNull() ?: return null
        return Triple(key, seq, raw.substringAfter('|'))
    }

    @Synchronized override fun get(key: String): String? = entries[key]

    @Synchronized override fun put(key: String, value: String) {
        entries.remove(key)
        entries[key] = value
        val edit = prefs.edit().putString(key, "${++sequence}|$value")
        while (entries.size > max) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
            edit.remove(oldest)
        }
        edit.apply()
    }

    @Synchronized override fun remove(key: String) {
        entries.remove(key)
        prefs.edit().remove(key).apply()
    }

    @Synchronized override fun clear() {
        entries.clear()
        prefs.edit().clear().apply()
    }

    override val size: Int @Synchronized get() = entries.size

    companion object {
        const val FILE = "ai_cache"
        private const val MAX_ENTRIES = 400
    }
}
