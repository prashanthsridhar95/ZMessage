package com.zyke.message.data.demo

import android.content.SharedPreferences

/**
 * A SharedPreferences that lives only in memory: nothing is ever written to a file. Demo mode hands one of these to every
 * store in place of the real preference files, so whatever is changed while demonstrating disappears with the process.
 */
class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized override fun getAll(): MutableMap<String, *> = HashMap(values).also { copy ->
        copy.entries.forEach { e -> (e.value as? Set<*>)?.let { e.setValue(HashSet(it)) } }
    }
    @Synchronized override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean { publish(); return true }
        override fun apply() { publish() }

        private fun publish() {
            val changed = ArrayList<String>()
            val heard: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(this@MemoryPrefs) {
                if (clear) { changed.addAll(values.keys); values.clear() }
                for ((key, value) in pending) {
                    if (value == null) { if (values.remove(key) != null) changed.add(key) } else { values[key] = value; changed.add(key) }
                }
                heard = listeners.toList()
            }
            pending.clear()
            clear = false
            for (key in changed.distinct()) for (l in heard) l.onSharedPreferenceChanged(this@MemoryPrefs, key)
        }
    }
}

/** The in-memory preference files of one Demo session, by name. The same name always gives the same file; names never mix. */
class MemoryPrefsStore {
    private val files = HashMap<String, MemoryPrefs>()

    @Synchronized fun get(name: String): MemoryPrefs = files.getOrPut(name) { MemoryPrefs() }

    @Synchronized fun delete(name: String): Boolean = files.remove(name) != null

    @Synchronized fun names(): List<String> = files.keys.sorted()
}
