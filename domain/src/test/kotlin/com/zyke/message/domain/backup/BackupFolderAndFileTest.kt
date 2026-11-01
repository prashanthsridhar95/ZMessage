package com.zyke.message.domain.backup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFolderAndFileTest {
    private val setup = "{\"snapshot\":1,\"prefs\":{}}".toByteArray()
    private val utc = ZoneId.of("UTC")

    @Test fun `a dated name is made from the local time and parses back`() {
        val at = Instant.parse("2026-11-01T02:03:09Z")
        val name = BackupFileNames.forTime(at, utc)
        assertEquals("zyke-backup-20261101-020309.zbk", name)
        assertEquals(LocalDateTime.of(2026, 11, 1, 2, 3, 9), BackupFileNames.parse(name))
        assertEquals("zyke-backup-20261101-073309.zbk", BackupFileNames.forTime(at, ZoneId.of("Asia/Kolkata")))
    }

    @Test fun `only exactly our names match`() {
        assertTrue(BackupFileNames.matches("zyke-backup-20261101-020309.zbk"))
        for (n in listOf(
            "zyke-backup-2026-11-01.zbk",            // the manual export
            "zyke-backup-20261101-020309 (1).zbk",   // a copy made by the file provider
            "zyke-backup-20261101-020309.zbk.bin",
            "zyke-backup.bin",
            "my-zyke-backup-20261101-020309.zbk",
            "zyke-backup-20261301-020309.zbk",       // month 13
            "zyke-backup-20261101-250309.zbk",       // hour 25
            "zyke-backup-20261101-0203.zbk", "notes.txt", ""
        )) assertFalse(n, BackupFileNames.matches(n))
        assertNull(BackupFileNames.parse("zyke-backup-20261101-020309.ZBK"))
    }

    private fun f(name: String) = FolderFile("id-$name", name)

    @Test fun `the newest seven are kept by the time in the name, not by list order`() {
        val names = (1..10).map { "zyke-backup-202611%02d-020000.zbk".format(it) }
        val deleted = FolderRetention.toDelete(names.shuffled(java.util.Random(3)).map(::f)).map { it.name }.toSet()
        assertEquals(names.take(3).toSet(), deleted)
        assertTrue(FolderRetention.toDelete(names.take(7).map(::f)).isEmpty())
        assertTrue(FolderRetention.toDelete(emptyList()).isEmpty())
    }

    @Test fun `foreign files never count and are never deleted`() {
        val ours = (1..8).map { "zyke-backup-202611%02d-020000.zbk".format(it) }
        val foreign = listOf("zyke-backup-2026-11-01.zbk", "photo.jpg", "zyke-backup-20261101-020000 (1).zbk", "zyke-backup.bin", "Zyke")
        val deleted = FolderRetention.toDelete((foreign + ours).map(::f)).map { it.name }
        assertEquals(listOf("zyke-backup-20261101-020000.zbk"), deleted)
        assertTrue(foreign.none { it in deleted })
        // Seven of ours plus many foreign: nothing to delete.
        assertTrue(FolderRetention.toDelete((foreign + ours.take(7)).map(::f)).isEmpty())
    }

    @Test fun `two backups in the same second keep a stable order`() {
        val a = f("zyke-backup-20261101-020000.zbk")
        assertEquals(listOf(a), FolderRetention.toDelete(listOf(a, f("zyke-backup-20261102-020000.zbk")), keep = 1))
    }

    @Test fun `the folder is named by its path when the provider gives one`() {
        assertEquals("Download/Zyke", FolderLabel.of("primary:Download/Zyke", "Zyke"))
        assertEquals("Documents", FolderLabel.of("1A2B-3C4D:Documents/", "Documents"))
        assertEquals("Backups", FolderLabel.of("primary:", "Backups"))
        assertEquals("Zyke", FolderLabel.of("some-opaque-id", " Zyke "))
        assertEquals("the chosen folder", FolderLabel.of(null, null))
    }

    @Test fun `the folder line reads like the others`() {
        val now = Instant.parse("2026-11-01T10:00:00Z")
        assertEquals(
            "Last saved to folder: today 02:03 · 312 KB · Download/Zyke",
            BackupText.lastSavedToFolder(Instant.parse("2026-11-01T02:03:00Z"), now, utc, 312L * 1024, "Download/Zyke", Locale.ENGLISH)
        )
        assertEquals(
            "Last backup: yesterday 02:03 · 312 KB · Google Drive",
            BackupText.lastBackup(Instant.parse("2026-10-31T02:03:00Z"), now, utc, 312L * 1024, "Google Drive", Locale.ENGLISH)
        )
    }

    // ---- choosing a file to restore from

    private val looksLike = { bytes: ByteArray -> BackupFileCheck.look(bytes.size.toLong(), bytes.copyOf(minOf(bytes.size, BackupFileCheck.HEAD_BYTES))) }

    @Test fun `every kind of file we write passes the quick look and opens with its PIN`() {
        val pin = "482915".toCharArray()
        val material = BackupCrypto.newKeyMaterial(pin, iterations = 1_000)
        val zipped = BackupContainer.pack(setup, mapOf("HDFCBK" to byteArrayOf(9))).bytes
        val scheduled = BackupCrypto.encryptWith(zipped, material, createdAt = 5)               // .zbk in the folder, or the .bin
        val manual = BackupCrypto.encrypt(setup, "a long passphrase".toCharArray(), createdAt = 6, iterations = 1_000) // zyke-backup v1 export, bare JSON inside

        for (file in listOf(scheduled, manual)) assertEquals(BackupFileCheck.Verdict.OK, looksLike(file.toByteArray()))

        val a = BackupCrypto.open(scheduled, pin)
        assertArrayEquals(setup, BackupContainer.unpack(a.plain).setup)
        assertEquals(1, BackupContainer.unpack(a.plain).pictures.size)

        val b = BackupCrypto.open(manual, "a long passphrase".toCharArray())
        val contents = BackupContainer.unpack(b.plain)
        assertArrayEquals(setup, contents.setup)
        assertFalse(contents.picturesIncluded)
        assertEquals(6L, BackupCrypto.peek(manual).createdAt)
    }

    @Test fun `things that are not backups are told apart before the PIN is asked`() {
        assertEquals(BackupFileCheck.Verdict.NOT_A_BACKUP, looksLike("hello world".toByteArray()))
        assertEquals(BackupFileCheck.Verdict.NOT_A_BACKUP, looksLike(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0)))       // a zip
        assertEquals(BackupFileCheck.Verdict.NOT_A_BACKUP, looksLike("{\"snapshot\":1,\"prefs\":{}}".toByteArray())) // a bare snapshot
        assertEquals(BackupFileCheck.Verdict.NOT_A_BACKUP, looksLike("{\"format\":\"other\"}".toByteArray()))
        assertEquals(BackupFileCheck.Verdict.EMPTY, BackupFileCheck.look(0, ByteArray(0)))
        assertEquals(BackupFileCheck.Verdict.TOO_BIG, BackupFileCheck.look(BackupFileCheck.MAX_BYTES + 1, "{\"format\":\"zyke-backup\"".toByteArray()))
        assertEquals(BackupFileCheck.Verdict.OK, BackupFileCheck.look(-1, "﻿ {\"format\" : \"zyke-backup\",\"version\":1".toByteArray()))
        assertEquals("This doesn't look like a Zyke backup.", BackupFileCheck.message(BackupFileCheck.Verdict.NOT_A_BACKUP))
    }

    @Test fun `a file that passes the look but is damaged is reported by the importer`() {
        val file = BackupCrypto.encrypt(setup, "a long passphrase".toCharArray(), iterations = 1_000)
        val damaged = file.replace("\"payload\":\"", "\"payload\":\"AAAA")
        assertEquals(BackupFileCheck.Verdict.OK, looksLike(damaged.toByteArray()))
        try {
            BackupCrypto.open(damaged, "a long passphrase".toCharArray())
            org.junit.Assert.fail("should not open")
        } catch (e: BackupException) {
            assertTrue(e is BackupException.WrongPassphrase || e is BackupException.Corrupt)
        }
    }

    // ---- first launch

    @Test fun `first launch decisions`() {
        val d = FirstRunDecision.Action.values().associateBy { it.name }
        fun decide(setup: Boolean, done: Boolean, file: Boolean, shows: Int = 0) = FirstRunDecision.decide(setup, done, file, shows)
        // A clean install, with or without a leftover Android backup file, is welcomed.
        assertEquals(d["WELCOME"], decide(false, false, false))
        assertEquals(d["WELCOME"], decide(false, false, true))
        // Already answered: never again, whatever else is on the phone.
        for (setup in listOf(true, false)) for (file in listOf(true, false)) assertEquals(d["SKIP"], decide(setup, true, file))
        // An update from an older build already holds a setup: no question, and it is remembered.
        assertEquals(d["SKIP_AND_MARK_DONE"], decide(true, false, false))
        assertEquals(d["SKIP_AND_MARK_DONE"], decide(true, false, true))
        // Shown several times without ever being answered: stop asking so nothing can trap the person.
        assertEquals(d["WELCOME"], decide(false, false, false, FirstRunDecision.MAX_SHOWS - 1))
        assertEquals(d["SKIP_AND_MARK_DONE"], decide(false, false, true, FirstRunDecision.MAX_SHOWS))
    }
}
