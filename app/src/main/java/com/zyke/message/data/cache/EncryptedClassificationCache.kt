package com.zyke.message.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.repository.ClassificationCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remembers how each message was read, in a small database on the phone, so the inbox is not parsed from scratch
 * on every start.
 *
 * Every entry is encrypted with a key that lives in the Android Keystore and never leaves it, so the database
 * file on its own is unreadable, and the data is excluded from backups. The cache is only a shortcut: if
 * anything about it is wrong (a new app version, a changed class, a damaged row) it is simply thrown away and
 * rebuilt from the messages.
 */
class EncryptedClassificationCache(context: Context, private val versionTag: String) : ClassificationCache {

    private val helper = Helper(context.applicationContext)

    /** Everything remembered. Only for small caches and tests: a huge inbox is read a month at a time through [load] with ids. */
    @Synchronized
    override fun load(): Map<Long, ClassifiedMessage> = readAll()

    @Synchronized
    override fun load(ids: Collection<Long>): Map<Long, ClassifiedMessage> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, ClassifiedMessage>(ids.size)
        val damaged = ArrayList<Long>()
        val db = helper.readableDatabase
        // SQLite caps the number of placeholders in one query, so the ids go in groups.
        ids.chunked(QUERY_CHUNK).forEach { group ->
            val marks = group.joinToString(",") { "?" }
            db.rawQuery("SELECT $ID, $PAYLOAD FROM $TABLE WHERE $ID IN ($marks)", Array(group.size) { group[it].toString() }).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val message = runCatching { deserialize(decrypt(cursor.getBlob(1))) }.getOrNull()
                    if (message != null) result[id] = message else damaged += id
                }
            }
        }
        if (damaged.isNotEmpty()) {
            val writable = helper.writableDatabase
            damaged.forEach { writable.delete(TABLE, "$ID = ?", arrayOf(it.toString())) }
        }
        return result
    }

    @Synchronized
    override fun save(messages: List<ClassifiedMessage>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            messages.forEach { message ->
                val payload = runCatching { encrypt(serialize(message)) }.getOrNull() ?: return@forEach
                db.insertWithOnConflict(
                    TABLE, null,
                    ContentValues().apply {
                        put(ID, message.id)
                        put(DATE, message.message.date)
                        put(PAYLOAD, payload)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun retainOnly(ids: Set<Long>) {
        // Only the ids are read, not the payloads, so this is cheap even when a million messages were remembered.
        val stored = ArrayList<Long>()
        helper.readableDatabase.rawQuery("SELECT $ID FROM $TABLE", null).use { while (it.moveToNext()) stored += it.getLong(0) }
        val gone = stored.filter { it !in ids }
        if (gone.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            gone.forEach { db.delete(TABLE, "$ID = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
        helper.writableDatabase.delete(META, "$KEY != ?", arrayOf(VERSION_KEY))
    }

    @Synchronized
    override fun meta(key: String): String? =
        helper.readableDatabase.rawQuery("SELECT $VALUE FROM $META WHERE $KEY = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized
    override fun putMeta(key: String, value: String) {
        helper.writableDatabase.insertWithOnConflict(
            META, null, ContentValues().apply { put(KEY, key); put(VALUE, value) }, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    override fun loadWindow(fromMillis: Long, untilMillis: Long): List<ClassifiedMessage> {
        val result = ArrayList<ClassifiedMessage>()
        val damaged = ArrayList<Long>()
        helper.readableDatabase.rawQuery(
            "SELECT $ID, $PAYLOAD FROM $TABLE WHERE $DATE >= ? AND $DATE < ? ORDER BY $DATE DESC",
            arrayOf(fromMillis.toString(), untilMillis.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val message = runCatching { deserialize(decrypt(cursor.getBlob(1))) }.getOrNull()
                if (message != null) result += message else damaged += cursor.getLong(0)
            }
        }
        if (damaged.isNotEmpty()) {
            val db = helper.writableDatabase
            damaged.forEach { db.delete(TABLE, "$ID = ?", arrayOf(it.toString())) }
        }
        return result
    }

    @Synchronized
    override fun dateRange(): Pair<Long, Long>? =
        helper.readableDatabase.rawQuery("SELECT MIN($DATE), MAX($DATE) FROM $TABLE", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) to it.getLong(1) else null
        }

    private fun readAll(): HashMap<Long, ClassifiedMessage> {
        val result = HashMap<Long, ClassifiedMessage>()
        val damaged = ArrayList<Long>()
        helper.readableDatabase.query(TABLE, arrayOf(ID, PAYLOAD), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val message = runCatching { deserialize(decrypt(cursor.getBlob(1))) }.getOrNull()
                if (message != null) result[id] = message else damaged += id
            }
        }
        if (damaged.isNotEmpty()) {
            val db = helper.writableDatabase
            damaged.forEach { db.delete(TABLE, "$ID = ?", arrayOf(it.toString())) }
        }
        return result
    }

    // ---- storage

    private inner class Helper(context: Context) : SQLiteOpenHelper(context, "classified.db", null, SCHEMA_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE ($ID INTEGER PRIMARY KEY, $DATE INTEGER NOT NULL DEFAULT 0, $PAYLOAD BLOB NOT NULL)")
            db.execSQL("CREATE INDEX classified_date ON $TABLE ($DATE)")
            db.execSQL("CREATE TABLE $META ($KEY TEXT PRIMARY KEY, $VALUE TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            db.execSQL("DROP TABLE IF EXISTS $META")
            onCreate(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (db.isReadOnly) return
            // A new app version may read messages differently, so what was remembered is no longer trusted.
            val stored = db.rawQuery("SELECT $VALUE FROM $META WHERE $KEY = ?", arrayOf(VERSION_KEY)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (stored != versionTag) {
                // What was remembered, and how far it had been read, is no longer trusted.
                db.delete(TABLE, null, null)
                db.delete(META, null, null)
                db.insertWithOnConflict(
                    META, null,
                    ContentValues().apply {
                        put(KEY, VERSION_KEY)
                        put(VALUE, versionTag)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }
    }

    // ---- encryption and serialisation

    private fun serialize(message: ClassifiedMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(message) }
        return bytes.toByteArray()
    }

    /** Only ever called on bytes that passed the authenticated decryption below, i.e. that this app wrote itself. */
    private fun deserialize(bytes: ByteArray): ClassifiedMessage =
        ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ClassifiedMessage }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
        }
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    /** One AES key for the cache, created on first use inside the Keystore. */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val SCHEMA_VERSION = 2
        const val QUERY_CHUNK = 500
        const val TABLE = "classified"
        const val META = "meta"
        const val ID = "id"
        const val DATE = "date"
        const val PAYLOAD = "payload"
        const val KEY = "k"
        const val VALUE = "v"
        const val VERSION_KEY = "version"

        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zyke_classification_cache"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
