package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.triage.TriageMode
import com.zyke.message.domain.triage.TriageRepository
import com.zyke.message.domain.triage.TriageSettings
import com.zyke.message.domain.triage.TriageStackSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The order and modes of Home's stacks, and the cards restored from "Auto cleared", in private preferences (`triage_prefs`). The
 * backup copies every preference file, so this goes with it; in Demo mode the preferences are in memory. Only what differs from
 * the defaults is written, and anything read back goes through [TriageSettings], which repairs unknown or disallowed values.
 */
class TriageRepositoryImpl(context: Context) : TriageRepository {

    private val prefs = context.applicationContext.getSharedPreferences("triage_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    override val settings: StateFlow<TriageSettings> = _settings.asStateFlow()

    private val _restored = MutableStateFlow(readRestored())
    override val restored: StateFlow<Set<Long>> = _restored.asStateFlow()

    private fun readSettings(): TriageSettings {
        val order = prefs.getString(KEY_ORDER, null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: TriageStackSpec.DEFAULT_ORDER
        val modes = HashMap<String, TriageMode>()
        for (spec in TriageStackSpec.ALL) {
            val mode = prefs.getString(MODE_PREFIX + spec.id, null)?.let { name -> TriageMode.entries.firstOrNull { it.name == name } } ?: continue
            modes[spec.id] = mode
        }
        return TriageSettings(order, modes)
    }

    private fun readRestored(): Set<Long> =
        prefs.getString(KEY_RESTORED, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toCollection(LinkedHashSet()) ?: emptySet()

    @Synchronized
    override fun save(settings: TriageSettings) {
        // Stored in the repaired form, so a restored file never carries an id or mode that is not allowed.
        val clean = settings.normalized()
        _settings.value = clean
        val edit = prefs.edit().clear()
        if (clean.orderedIds() != TriageStackSpec.DEFAULT_ORDER) edit.putString(KEY_ORDER, clean.orderedIds().joinToString(","))
        clean.modes.forEach { (id, mode) -> edit.putString(MODE_PREFIX + id, mode.name) }
        _restored.value.takeIf { it.isNotEmpty() }?.let { edit.putString(KEY_RESTORED, it.joinToString(",")) }
        edit.apply()
    }

    @Synchronized
    override fun restore(messageId: Long) {
        // The newest few hundred only: a restored card is a recent one.
        _restored.value = (_restored.value + messageId).let { all -> if (all.size > MAX_RESTORED) all.drop(all.size - MAX_RESTORED).toSet() else all }
        prefs.edit().putString(KEY_RESTORED, _restored.value.joinToString(",")).apply()
    }

    @Synchronized
    override fun unrestore(messageId: Long) {
        _restored.value = _restored.value - messageId
        val edit = prefs.edit()
        if (_restored.value.isEmpty()) edit.remove(KEY_RESTORED) else edit.putString(KEY_RESTORED, _restored.value.joinToString(","))
        edit.apply()
    }

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_RESTORED = "restored"
        const val MODE_PREFIX = "mode_"
        const val MAX_RESTORED = 300
    }
}
