package com.zyke.message.domain.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    private val pass = "correct horse".toCharArray()
    private val fast = 1_000

    private fun seal(plain: ByteArray = "hello".toByteArray(), p: CharArray = pass, at: Long = 42L) =
        BackupCrypto.encrypt(plain, p, createdAt = at, iterations = fast)

    private inline fun <reified E : BackupException> fails(block: () -> Unit) {
        try {
            block()
            fail("expected ${E::class.simpleName}")
        } catch (e: BackupException) {
            assertTrue("was ${e::class.simpleName}", e is E)
        }
    }

    @Test fun `round trip keeps every byte including unicode`() {
        val plain = "₹ 1,234 – नमस्ते \"quoted\" \n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(plain, BackupCrypto.decrypt(seal(plain), pass))
    }

    @Test fun `round trip with the default strength`() {
        val file = BackupCrypto.encrypt("x".toByteArray(), pass)
        assertTrue(file.contains("\"iterations\":310000"))
        assertArrayEquals("x".toByteArray(), BackupCrypto.decrypt(file, pass))
    }

    @Test fun `empty payload round trips`() {
        assertEquals(0, BackupCrypto.decrypt(seal(ByteArray(0)), pass).size)
    }

    @Test fun `wrong passphrase fails`() {
        fails<BackupException.WrongPassphrase> { BackupCrypto.decrypt(seal(), "wrong horse".toCharArray()) }
    }

    @Test fun `same input gives a different file each time`() {
        assertNotEquals(seal(), seal())
    }

    @Test fun `tampered ciphertext fails`() {
        val file = seal("secret data here".toByteArray())
        val i = file.indexOf("\"payload\":\"") + "\"payload\":\"".length
        val flipped = if (file[i] == 'A') 'B' else 'A'
        fails<BackupException> { BackupCrypto.decrypt(file.substring(0, i) + flipped + file.substring(i + 1), pass) }
    }

    @Test fun `editing the version or format label is detected`() {
        val file = seal()
        fails<BackupException.NotABackup> { BackupCrypto.decrypt(file.replace("zyke-backup", "other-thing"), pass) }
    }

    @Test fun `newer version is reported as such even with the right passphrase`() {
        val newer = seal().replace("\"version\":1", "\"version\":2")
        val e = try { BackupCrypto.decrypt(newer, pass); null } catch (e: BackupException.NewerVersion) { e }
        assertEquals(2, e?.version)
        fails<BackupException.NewerVersion> { BackupCrypto.peek(newer) }
    }

    @Test fun `peek reads version and date without a passphrase`() {
        val h = BackupCrypto.peek(seal(at = 1234L))
        assertEquals(BackupHeader(1, 1234L), h)
    }

    @Test fun `not a backup`() {
        fails<BackupException.NotABackup> { BackupCrypto.decrypt("", pass) }
        fails<BackupException.NotABackup> { BackupCrypto.decrypt("hello world", pass) }
        fails<BackupException.NotABackup> { BackupCrypto.decrypt("[1,2]", pass) }
        fails<BackupException.NotABackup> { BackupCrypto.decrypt("{\"a\":1}", pass) }
        fails<BackupException.NotABackup> { BackupCrypto.decrypt("{\"format\":\"zyke-backup\"", pass) }
    }

    @Test fun `damaged fields are corrupt not a crash`() {
        val file = seal()
        fails<BackupException.Corrupt> { BackupCrypto.decrypt(file.replace("\"payload\":\"", "\"payload\":\"!!"), pass) }
        fails<BackupException.Corrupt> { BackupCrypto.decrypt(file.replace("\"iterations\":1000", "\"iterations\":99999999999"), pass) }
        fails<BackupException.Corrupt> { BackupCrypto.decrypt("{\"format\":\"zyke-backup\",\"version\":1}", pass) }
    }

    @Test fun `a changed iteration count cannot open the file`() {
        fails<BackupException> { BackupCrypto.decrypt(seal().replace("\"iterations\":1000", "\"iterations\":1001"), pass) }
    }

    @Test fun `file is plain json with the documented shape`() {
        val file = seal()
        assertTrue(file.startsWith("{\"format\":\"zyke-backup\",\"version\":1,\"createdAt\":42,"))
        assertTrue(file.contains("PBKDF2WithHmacSHA256"))
        assertTrue(file.contains("AES-256-GCM"))
        assertFalse(file.contains("hello"))
    }

    @Test fun `minimum passphrase length is eight`() {
        assertEquals(8, BackupCrypto.MIN_PASSPHRASE_LENGTH)
    }
}
