package com.zyke.message.domain.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup could not be opened. Each case has a plain sentence the person can read. */
sealed class BackupException(val reason: String) : Exception(reason) {
    /** The file is not a Zyke backup at all. */
    class NotABackup : BackupException("This file is not a Zyke backup.")

    /** The file was written by a newer version of the app; [version] is the one it was written with. */
    class NewerVersion(val version: Int) : BackupException("This backup was made by a newer version of the app. Update the app and try again.")

    /** Wrong passphrase, or the file was changed after it was saved; the two cannot be told apart. */
    class WrongPassphrase : BackupException("That passphrase does not open this backup, or the file has been changed.")

    /** It looks like a backup but is damaged or has unusable settings. */
    class Corrupt : BackupException("This backup is damaged and cannot be read.")
}

/** The readable outside of a backup file (nothing secret): when it was made and with which format version. */
data class BackupHeader(val version: Int, val createdAt: Long)

/**
 * Passphrase-protected backup files: a small JSON envelope around AES-256-GCM, with the key derived from the
 * passphrase by PBKDF2-HMAC-SHA256 and a fresh random salt and nonce each time. Pure JVM, no Android.
 *
 * Format version 1:
 * `{"format":"zyke-backup","version":1,"createdAt":<millis>,"kdf":{"alg":"PBKDF2WithHmacSHA256","iterations":310000,"salt":"<b64>"},
 * "cipher":{"alg":"AES-256-GCM","iv":"<b64>"},"payload":"<b64 of ciphertext and tag>"}`
 * The format name and version are bound into the encryption as associated data, so they cannot be edited without
 * the file failing to open.
 */
object BackupCrypto {
    const val FORMAT = "zyke-backup"
    const val VERSION = 1
    const val DEFAULT_ITERATIONS = 310_000
    const val MIN_PASSPHRASE_LENGTH = 8

    private const val KDF_ALG = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALG = "AES-256-GCM"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val MAX_ITERATIONS = 5_000_000

    fun encrypt(
        plain: ByteArray,
        passphrase: CharArray,
        createdAt: Long = System.currentTimeMillis(),
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom()
    ): String {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val sealed = cipher(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), iv, VERSION).doFinal(plain)
        val b64 = Base64.getEncoder()
        return buildString {
            append("{\"format\":\"").append(FORMAT).append("\",\"version\":").append(VERSION)
            append(",\"createdAt\":").append(createdAt)
            append(",\"kdf\":{\"alg\":\"").append(KDF_ALG).append("\",\"iterations\":").append(iterations)
            append(",\"salt\":\"").append(b64.encodeToString(salt)).append("\"}")
            append(",\"cipher\":{\"alg\":\"").append(CIPHER_ALG).append("\",\"iv\":\"").append(b64.encodeToString(iv)).append("\"}")
            append(",\"payload\":\"").append(b64.encodeToString(sealed)).append("\"}")
        }
    }

    fun encrypt(plain: String, passphrase: CharArray, createdAt: Long = System.currentTimeMillis()): String =
        encrypt(plain.toByteArray(Charsets.UTF_8), passphrase, createdAt)

    /** Reads only the outside of the file, to tell the person what it is before asking for the passphrase. */
    @Throws(BackupException::class)
    fun peek(file: String): BackupHeader {
        val env = parseEnvelope(file)
        return BackupHeader(env.version, env.createdAt)
    }

    /** Opens a backup. Throws a [BackupException] saying why when it cannot. */
    @Throws(BackupException::class)
    fun decrypt(file: String, passphrase: CharArray): ByteArray {
        val env = parseEnvelope(file)
        val key = try {
            deriveKey(passphrase, env.salt, env.iterations)
        } catch (e: GeneralSecurityException) {
            throw BackupException.Corrupt()
        }
        return try {
            cipher(Cipher.DECRYPT_MODE, key, env.iv, env.version).doFinal(env.payload)
        } catch (e: GeneralSecurityException) {
            throw BackupException.WrongPassphrase()
        }
    }

    private class Envelope(val version: Int, val createdAt: Long, val iterations: Int, val salt: ByteArray, val iv: ByteArray, val payload: ByteArray)

    private fun parseEnvelope(file: String): Envelope {
        val root = try {
            MiniJson.parse(file) as? Map<*, *>
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw BackupException.NotABackup()
        if (root["format"] != FORMAT) throw BackupException.NotABackup()
        val version = (root["version"] as? Long)?.toInt() ?: throw BackupException.Corrupt()
        if (version > VERSION) throw BackupException.NewerVersion(version)
        if (version < 1) throw BackupException.Corrupt()
        val kdf = root["kdf"] as? Map<*, *> ?: throw BackupException.Corrupt()
        val cipher = root["cipher"] as? Map<*, *> ?: throw BackupException.Corrupt()
        if (kdf["alg"] != KDF_ALG || cipher["alg"] != CIPHER_ALG) throw BackupException.Corrupt()
        val iterations = (kdf["iterations"] as? Long)?.takeIf { it in 1..MAX_ITERATIONS }?.toInt() ?: throw BackupException.Corrupt()
        val b64 = Base64.getDecoder()
        return try {
            Envelope(
                version = version,
                createdAt = root["createdAt"] as? Long ?: 0L,
                iterations = iterations,
                salt = b64.decode(kdf["salt"] as? String ?: throw BackupException.Corrupt()),
                iv = b64.decode(cipher["iv"] as? String ?: throw BackupException.Corrupt()),
                payload = b64.decode(root["payload"] as? String ?: throw BackupException.Corrupt())
            )
        } catch (e: IllegalArgumentException) {
            throw BackupException.Corrupt()
        }.also { if (it.iv.size != IV_BYTES || it.salt.isEmpty()) throw BackupException.Corrupt() }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(KDF_ALG).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, iv: ByteArray, version: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD("$FORMAT:$version".toByteArray(Charsets.UTF_8))
        }
}
