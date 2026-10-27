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
    private var memory: HashMap<Long, ClassifiedMessage>? = null

    @Synchronized
    override fun load(): Map<Long, ClassifiedMessage> = memory ?: readAll().also { memory = it }

    @Synchronized
    override fun save(messages: List<ClassifiedMessage>) {
        val loaded = memory ?: readAll().also { memory = it }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            messages.forEach { message ->
                val payload = runCatching { encrypt(serialize(message)) }.getOrNull() ?: return@forEach
                db.insertWithOnConflict(
                    TABLE, null,
                    ContentValues().apply {
                        put(ID, message.id)
                        put(PAYLOAD, payload)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                loaded[message.id] = message
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun retainOnly(ids: Set<Long>) {
        val loaded = memory ?: readAll().also { memory = it }
        val gone = loaded.keys.filter { it !in ids }
        if (gone.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            gone.forEach { db.delete(TABLE, "$ID = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        gone.forEach(loaded::remove)
    }

    @Synchronized
    override fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
        memory = HashMap()
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
            db.execSQL("CREATE TABLE $TABLE ($ID INTEGER PRIMARY KEY, $PAYLOAD BLOB NOT NULL)")
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
                db.delete(TABLE, null, null)
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
        const val SCHEMA_VERSION = 1
        const val TABLE = "classified"
        const val META = "meta"
        const val ID = "id"
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
