package com.zyke.message.data.ai

import android.content.Context

/**
 * When the model last gave a real answer on this phone, and how long it took: two numbers, never a prompt or an answer. They live
 * in the `ai_settings` preferences, which no backup carries, because they describe this phone. A restored phone starts with none.
 */
class ModelEvidenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(AiSettingsRepositoryImpl.FILE, Context.MODE_PRIVATE)

    fun workedAtMs(): Long? = prefs.getLong(KEY_AT, NONE).takeIf { it > NONE }

    fun latencyMs(): Long? = prefs.getLong(KEY_MS, NONE).takeIf { it > NONE }

    fun save(atMs: Long, latencyMs: Long) {
        prefs.edit().putLong(KEY_AT, atMs).putLong(KEY_MS, latencyMs).apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_AT).remove(KEY_MS).apply()
    }

    private companion object {
        const val KEY_AT = "worked_at_ms"
        const val KEY_MS = "worked_latency_ms"
        const val NONE = 0L
    }
}
