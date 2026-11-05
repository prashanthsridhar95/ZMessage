package com.zyke.message.data.demo

import android.content.Context

/**
 * Whether the app is showing the made-up Demo setup instead of the person's own data.
 *
 * The switch is one boolean in its own small preference file (`demo_mode`), which is left out of every backup and is
 * the only thing Demo mode writes to real storage. It is read once, first thing in `Application.onCreate`, and the
 * process is restarted to change it, so a process is wholly one or the other: the container is built either from the
 * real stores or from in-memory ones, never a mixture. It survives the process being killed in the background, so
 * coming back to a demonstration never shows the real data; only "Exit demo mode" clears it.
 */
object DemoMode {
    const val PREFS = "demo_mode"
    private const val KEY = "on"

    @Volatile var active: Boolean = false
        private set

    /** Reads the saved switch. Never throws: if it cannot be read, the real app runs. */
    fun init(context: Context) {
        active = isSaved(context)
    }

    fun isSaved(context: Context): Boolean =
        runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false) }.getOrDefault(false)

    /** Writes the switch straight to the file (the process is restarted right after). Returns whether it was saved. */
    fun save(context: Context, on: Boolean): Boolean =
        runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).commit() }.getOrDefault(false)

    /** Removes what a finished Demo mode left in the cache folder. Cheap; safe to call at any start. */
    fun cleanUp(context: Context) {
        runCatching { DemoContext.scratchDir(context.applicationContext).deleteRecursively() }
    }
}
