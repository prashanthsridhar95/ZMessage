package com.zyke.message.data

import android.app.NotificationManager
import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * Deletes everything the app has kept on the phone: what it read from messages (and the key that protects it), the
 * person's setup, entries, pictures and caches. The messages in the phone's own inbox are not touched; the app never
 * writes to them. The caller closes the app straight afterwards, so nothing in memory writes it back.
 */
object DataWipe {
    private const val CACHE_KEY_ALIAS = "zyke_classification_cache"

    fun wipe(context: Context) {
        val app = context.applicationContext
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancelAll() }
        runCatching { com.zyke.message.notifications.DigestScheduler(app).apply(false) }
        runCatching { com.zyke.message.notifications.LiveAlerts.setEnabled(app, false) }

        // Every preference file, whatever its name.
        File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { app.deleteSharedPreferences(it.nameWithoutExtension) }
        app.databaseList().forEach { app.deleteDatabase(it) }
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(CACHE_KEY_ALIAS) }
    }
}
