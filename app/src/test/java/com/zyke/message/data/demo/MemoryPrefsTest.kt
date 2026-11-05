package com.zyke.message.data.demo

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory preference files Demo mode swaps in for the real ones: they must behave like preferences and never mix. */
class MemoryPrefsTest {
    @Test fun storesEveryKindAndReadsItBack_withDefaultsWhenMissing() {
        val p = MemoryPrefs()
        p.edit().putString("s", "a").putInt("i", 3).putLong("l", 4L).putFloat("f", 1.5f).putBoolean("b", true).putStringSet("set", setOf("x", "y")).apply()
        assertEquals("a", p.getString("s", null))
        assertEquals(3, p.getInt("i", 0))
        assertEquals(4L, p.getLong("l", 0))
        assertEquals(1.5f, p.getFloat("f", 0f), 0f)
        assertTrue(p.getBoolean("b", false))
        assertEquals(setOf("x", "y"), p.getStringSet("set", null))
        assertEquals("d", p.getString("missing", "d"))
        assertEquals(7, p.getInt("s", 7)) // the wrong type reads as missing, like the real thing would throw; never crash a store
        assertEquals(6, p.all.size)
    }

    @Test fun removeAndClearWork_andCommitReportsSuccess() {
        val p = MemoryPrefs()
        assertTrue(p.edit().putString("a", "1").putString("b", "2").commit())
        p.edit().remove("a").apply()
        assertFalse(p.contains("a"))
        assertTrue(p.contains("b"))
        // As with the real thing, clear() is applied first, then the puts of the same edit.
        p.edit().putString("c", "3").clear().apply()
        assertEquals(setOf("c"), p.all.keys)
        p.edit().clear().apply()
        assertTrue(p.all.isEmpty())
        p.edit().putString("a", "1").apply()
        p.edit().putString("a", null).apply()
        assertFalse(p.contains("a"))
    }

    @Test fun aReturnedSetIsACopy_soTheStoredOneCannotBeChangedBehindItsBack() {
        val p = MemoryPrefs()
        val mine = mutableSetOf("x")
        p.edit().putStringSet("k", mine).apply()
        mine.add("y")
        assertEquals(setOf("x"), p.getStringSet("k", null))
        p.getStringSet("k", null)!!.add("z")
        assertEquals(setOf("x"), p.getStringSet("k", null))
    }

    @Test fun listenersHearChangesUntilTheyAreRemoved() {
        val p = MemoryPrefs()
        val heard = ArrayList<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard.add(key.orEmpty()) }
        p.registerOnSharedPreferenceChangeListener(listener)
        p.edit().putString("a", "1").apply()
        p.unregisterOnSharedPreferenceChangeListener(listener)
        p.edit().putString("b", "1").apply()
        assertEquals(listOf("a"), heard)
    }

    @Test fun theStoreGivesTheSameFileForTheSameNameAndNeverMixesNames() {
        val store = MemoryPrefsStore()
        val settings = store.get("settings")
        assertSame(settings, store.get("settings"))
        assertNotSame(settings, store.get("goals"))
        settings.edit().putBoolean("app_lock", true).apply()
        assertFalse(store.get("goals").contains("app_lock"))
        assertEquals(listOf("goals", "settings"), store.names())
        // A second store (a second Demo session) starts empty: nothing carries over between sessions.
        assertNull(MemoryPrefsStore().get("settings").getString("anything", null))
        assertTrue(store.delete("settings"))
        assertFalse(store.get("settings").contains("app_lock"))
    }
}
