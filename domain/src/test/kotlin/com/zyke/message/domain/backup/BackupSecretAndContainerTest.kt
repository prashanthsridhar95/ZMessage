package com.zyke.message.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupSecretAndContainerTest {
    private val setup = "{\"snapshot\":1,\"prefs\":{}}".toByteArray()

    @Test fun `a six digit PIN, a passphrase, and nothing else are accepted`() {
        assertEquals(BackupSecret.Kind.PIN, BackupSecret.kindOf("482915".toCharArray()))
        assertEquals(BackupSecret.Kind.PASSPHRASE, BackupSecret.kindOf("correct horse".toCharArray()))
        assertEquals(BackupSecret.Kind.PASSPHRASE, BackupSecret.kindOf("12345678".toCharArray()))
        assertNull(BackupSecret.kindOf("1234".toCharArray()))
        assertNull(BackupSecret.kindOf("12345".toCharArray()))
        assertNull(BackupSecret.kindOf("abcdef".toCharArray()))
        assertNull(BackupSecret.kindOf("".toCharArray()))
    }

    @Test fun `obvious PINs are recognised`() {
        for (p in listOf("111111", "123456", "654321", "000000")) assertTrue(p, BackupSecret.isObviousPin(p.toCharArray()))
        for (p in listOf("482915", "121212", "135790")) assertFalse(p, BackupSecret.isObviousPin(p.toCharArray()))
    }

    @Test fun `a file made without the PIN opens on a new install with only the PIN`() {
        val pin = "482915".toCharArray()
        // Set-up on the old phone: the PIN is stretched once and only the key is kept.
        val material = BackupCrypto.newKeyMaterial(pin, iterations = 1_000)
        val container = BackupContainer.pack(setup, mapOf("HDFCBK" to byteArrayOf(1, 2, 3))).bytes
        val first = BackupCrypto.encryptWith(container, material, createdAt = 10)
        val second = BackupCrypto.encryptWith(container, material, createdAt = 20)
        assertTrue("a fresh nonce each time", first != second)

        // New phone: nothing but the file and what is in the person's head.
        val opened = BackupCrypto.open(second, "482915".toCharArray())
        assertEquals(20L, opened.createdAt)
        assertArrayEquals(material.keyBytes(), opened.material.keyBytes())
        val contents = BackupContainer.unpack(opened.plain)
        assertArrayEquals(setup, contents.setup)
        assertArrayEquals(byteArrayOf(1, 2, 3), contents.pictures["HDFCBK"])
        assertTrue(contents.picturesIncluded)
        // The same file also opens through the manual-restore path.
        assertArrayEquals(opened.plain, BackupCrypto.decrypt(first, pin))
        // And the key from the restore makes the next backup openable by the same PIN.
        val next = BackupCrypto.encryptWith(opened.plain, opened.material)
        assertArrayEquals(opened.plain, BackupCrypto.decrypt(next, pin))
    }

    @Test fun `the wrong PIN is reported as such`() {
        val material = BackupCrypto.newKeyMaterial("482915".toCharArray(), iterations = 1_000)
        val file = BackupCrypto.encryptWith(setup, material)
        try {
            BackupCrypto.open(file, "482916".toCharArray())
            fail()
        } catch (e: BackupException.WrongPassphrase) {
            // expected
        }
    }

    @Test fun `the file header carries salt and iterations but never the key or PIN`() {
        val material = BackupCrypto.newKeyMaterial("482915".toCharArray(), iterations = 1_000)
        val file = BackupCrypto.encryptWith(setup, material)
        assertTrue(file.contains("\"iterations\":1000"))
        assertFalse(file.contains("482915"))
        assertFalse(file.contains(java.util.Base64.getEncoder().encodeToString(material.keyBytes())))
    }

    @Test fun `strong iterations are at least 600000`() {
        assertTrue(BackupCrypto.STRONG_ITERATIONS >= 600_000)
    }

    @Test fun `old manual exports (bare JSON) still unpack`() {
        val old = BackupCrypto.encrypt(setup, "a passphrase".toCharArray())
        val contents = BackupContainer.unpack(BackupCrypto.decrypt(old, "a passphrase".toCharArray()))
        assertArrayEquals(setup, contents.setup)
        assertTrue(contents.pictures.isEmpty())
        assertFalse(contents.picturesIncluded)
    }

    @Test fun `pictures that are too big are skipped and the setup still saved`() {
        val big = mapOf("a" to ByteArray(12 * 1024 * 1024), "b" to ByteArray(12 * 1024 * 1024))
        val packed = BackupContainer.pack(setup, big)
        assertTrue(packed.picturesSkipped)
        val contents = BackupContainer.unpack(packed.bytes)
        assertTrue(contents.pictures.isEmpty())
        assertTrue(contents.picturesSkipped)
        assertFalse(contents.picturesIncluded)
        assertArrayEquals(setup, contents.setup)
    }

    @Test fun `sender keys with odd characters survive`() {
        val key = "Ravi's Café / ₹ #1"
        val contents = BackupContainer.unpack(BackupContainer.pack(setup, mapOf(key to byteArrayOf(9))).bytes)
        assertArrayEquals(byteArrayOf(9), contents.pictures[key])
    }

    @Test fun `garbage is reported as corrupt`() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(1, 2, 3, 4, 5), "PK\u0003\u0004garbage".toByteArray())) {
            try {
                BackupContainer.unpack(bad)
                fail()
            } catch (e: BackupException.Corrupt) {
                // expected
            }
        }
    }

    @Test fun `texts match what the screen shows`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-11-01T10:00:00Z")
        assertEquals("Last backup: today 02:03 · 312 KB · Google Drive",
            BackupText.lastBackup(Instant.parse("2026-11-01T02:03:00Z"), now, zone, 312 * 1024L, "Google Drive", Locale.ENGLISH))
        assertEquals("Last backup: yesterday 02:03 · 1.5 MB · This phone",
            BackupText.lastBackup(Instant.parse("2026-10-31T02:03:00Z"), now, zone, 1_572_864, "This phone", Locale.ENGLISH))
        assertEquals("Last backup: Wed 28 Oct 02:03 · 900 B · This phone",
            BackupText.lastBackup(Instant.parse("2026-10-28T02:03:00Z"), now, zone, 900, "This phone", Locale.ENGLISH))
        assertEquals("Next backup: Mon 2 Nov, 2:00 am", BackupText.nextBackup(Instant.parse("2026-11-02T02:00:00Z"), now, zone, Locale.ENGLISH))
        assertEquals("Next backup: as soon as Android allows", BackupText.nextBackup(now, now, zone, Locale.ENGLISH))
    }

    @Test fun `only the newest seven Drive backups are kept`() {
        val all = (1..10).map { RemoteBackup("id$it", Instant.ofEpochSecond(1_000L * it), 10) }.shuffled()
        assertEquals(setOf("id1", "id2", "id3"), DriveRetention.toDelete(all).map { it.id }.toSet())
        assertTrue(DriveRetention.toDelete(all.take(7)).isEmpty())
    }
}
