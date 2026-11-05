package com.zyke.message.data.demo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * What every store sees in Demo mode instead of the real [Context]. Preference files are in-memory ([MemoryPrefsStore]),
 * the private folders ([getFilesDir], [getCacheDir], databases) are a scratch folder that is emptied when Demo mode
 * starts and ends, and [getApplicationContext] is this wrapper, so a store that asks for "the application" still gets the
 * demo. The real app's preferences and files can therefore not be reached through it by accident: there is no path from
 * here to them.
 *
 * Not for system services: windows, resources and the like still come from the real context.
 */
@Suppress("TooManyFunctions") // every one is an override that keeps a door to the real storage shut
class DemoContext(base: Context) : ContextWrapper(base) {
    private val prefs = MemoryPrefsStore()

    /** The scratch folder. It lives in the cache folder, so even a leftover is only a cache. */
    private val scratch: File = scratchDir(base).apply { deleteRecursively(); mkdirs() }

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.get(name)
    override fun deleteSharedPreferences(name: String): Boolean = prefs.delete(name)
    override fun moveSharedPreferencesFrom(sourceContext: Context, name: String): Boolean = false

    override fun getFilesDir(): File = File(scratch, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(scratch, "cache").apply { mkdirs() }
    override fun getCodeCacheDir(): File = File(scratch, "code_cache").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(scratch, "no_backup").apply { mkdirs() }
    override fun getDir(name: String, mode: Int): File = File(scratch, "dir_$name").apply { mkdirs() }
    override fun getFileStreamPath(name: String): File = File(filesDir, name)
    override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream = FileOutputStream(getFileStreamPath(name), mode and MODE_APPEND != 0)
    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
    override fun fileList(): Array<String> = filesDir.list()?.toList()?.toTypedArray() ?: emptyArray()
    override fun getDatabasePath(name: String): File = File(File(scratch, "databases").apply { mkdirs() }, name)
    override fun databaseList(): Array<String> = File(scratch, "databases").list()?.toList()?.toTypedArray() ?: emptyArray()
    override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)

    /** Empties the scratch folder (Demo mode is over). */
    fun discard() {
        runCatching { scratch.deleteRecursively() }
    }

    companion object {
        fun scratchDir(context: Context): File = File(context.cacheDir, "demo_mode")
    }
}
