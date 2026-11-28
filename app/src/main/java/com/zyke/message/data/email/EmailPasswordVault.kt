package com.zyke.message.data.email

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zyke.message.domain.email.EmailCredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the person's app password for their mailbox. It is sealed with an AES-256-GCM key that lives in the Android Keystore and cannot be
 * taken out of it; what is written to the private `email_secret` file is useless without that key. The file is left out of every backup
 * (see `PrefsBackup`, `backup_rules.xml`, `data_extraction_rules.xml`), and a new phone or a reinstall has neither, so the person types
 * a new app password once. Never logged, never put in a report. The same mechanism as the backup key (`BackupKeyStore`), with its own
 * key alias so disconnecting email cannot touch backups and the other way round.
 */
internal class EmailPasswordVault(context: Context) : EmailCredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun hasPassword(): Boolean = prefs.contains(BLOB)

    @Synchronized
    override fun password(): String? = runCatching {
        val blob = Base64.decode(prefs.getString(BLOB, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        val raw = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        try { String(raw, Charsets.UTF_8) } finally { raw.fill(0) }
    }.getOrNull()

    @Synchronized
    override fun save(password: String): Boolean = runCatching {
        val raw = password.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.doFinal(raw)
            prefs.edit().putString(BLOB, Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)).commit()
        } finally {
            raw.fill(0)
        }
    }.getOrDefault(false)

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val FILE = "email_secret"
        /** The Keystore alias; `DataWipe` removes it with everything else. */
        const val ALIAS = "zyke_email_password"
        private const val BLOB = "blob"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}
