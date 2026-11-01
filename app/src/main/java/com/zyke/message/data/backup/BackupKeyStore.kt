package com.zyke.message.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zyke.message.domain.backup.BackupCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the backup key (stretched once from the PIN or passphrase; never the PIN itself) so automatic backups can be
 * made without asking. The key is sealed with an AES key that lives in the Android Keystore and cannot be taken out
 * of it; what is stored in the private `backup_secret` file (never backed up) is useless without that Keystore key.
 * A new phone or a reinstall has neither, so the person types the PIN once and the key is derived again.
 */
class BackupKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backup_secret", Context.MODE_PRIVATE)

    fun has(): Boolean = prefs.contains("blob")

    @Synchronized
    fun save(material: BackupCrypto.KeyMaterial) {
        val raw = material.keyBytes()
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val sealed = cipher.doFinal(raw)
            prefs.edit()
                .putString("salt", Base64.encodeToString(material.salt, Base64.NO_WRAP))
                .putInt("iterations", material.iterations)
                .putString("blob", Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP))
                .commit()
        } finally {
            raw.fill(0)
        }
    }

    /** The saved key, or null when there is none or the Keystore no longer holds the key that sealed it. */
    @Synchronized
    fun load(): BackupCrypto.KeyMaterial? = runCatching {
        val blob = Base64.decode(prefs.getString("blob", null) ?: return null, Base64.NO_WRAP)
        val salt = Base64.decode(prefs.getString("salt", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        val raw = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        try {
            BackupCrypto.KeyMaterial(salt, prefs.getInt("iterations", BackupCrypto.STRONG_ITERATIONS), raw)
        } finally {
            raw.fill(0)
        }
    }.getOrNull()

    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "zyke_backup_wrap"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
