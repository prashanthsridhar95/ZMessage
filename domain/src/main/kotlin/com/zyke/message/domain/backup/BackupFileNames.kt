package com.zyke.message.domain.backup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The names of the dated backup files written to a folder the person chose: `zyke-backup-yyyyMMdd-HHmmss.zbk`
 * (the phone's local time). The manual export is named `zyke-backup-yyyy-MM-dd.zbk`, which does NOT match, so
 * retention never touches it. Pure: no Android, so the pattern, the timestamp parsing and the selection are tested.
 */
object BackupFileNames {
    const val PREFIX = "zyke-backup-"
    const val EXTENSION = ".zbk"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
    private val PATTERN = Regex("^zyke-backup-(\\d{8}-\\d{6})\\.zbk$")

    fun forTime(at: Instant, zone: ZoneId): String = PREFIX + STAMP.format(at.atZone(zone)) + EXTENSION

    fun matches(name: String): Boolean = parse(name) != null

    /** The local time written in [name], or null when [name] is not one of ours (or the time is not a real one). */
    fun parse(name: String): LocalDateTime? {
        val stamp = PATTERN.matchEntire(name)?.groupValues?.get(1) ?: return null
        return try {
            LocalDateTime.parse(stamp, STAMP)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/** One file found in the folder: [ref] is whatever the caller needs to delete it again (a document id). */
data class FolderFile(val ref: String, val name: String)

/** How many dated files the folder keeps, and which to delete. */
object FolderRetention {
    const val KEEP = 7

    /**
     * The files beyond the newest [keep] of OUR dated backups, newest by the time in the name. Anything whose name is
     * not exactly ours (the person's own files, manual exports, copies like "name (1).zbk") is never returned.
     */
    fun toDelete(all: List<FolderFile>, keep: Int = KEEP): List<FolderFile> =
        all.mapNotNull { f -> BackupFileNames.parse(f.name)?.let { it to f } }
            .sortedWith(compareByDescending<Pair<LocalDateTime, FolderFile>> { it.first }.thenByDescending { it.second.name })
            .drop(keep.coerceAtLeast(0))
            .map { it.second }
}

/** The short text that says which folder is meant, from what the system's file provider reports. */
object FolderLabel {
    /**
     * "Download/Zyke" for a tree whose document id is `primary:Download/Zyke`; otherwise the display name; otherwise
     * a plain "the chosen folder". The volume part before the colon ("primary", a card's id) is never shown.
     */
    fun of(treeDocumentId: String?, displayName: String?): String {
        val id = treeDocumentId.orEmpty()
        val path = if (':' in id) id.substringAfter(':').trim('/') else ""
        if (path.isNotEmpty()) return path
        return displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "the chosen folder"
    }
}

/** A cheap look at a chosen file before the PIN is asked for. */
object BackupFileCheck {
    /** Bigger than this is refused without reading it all (a backup with pictures is a few MB at most). */
    const val MAX_BYTES = 50L * 1024 * 1024
    const val HEAD_BYTES = 512
    private val MAGIC = Regex("^\\uFEFF?\\s*\\{\\s*\"format\"\\s*:\\s*\"${BackupCrypto.FORMAT}\"")

    enum class Verdict { OK, EMPTY, TOO_BIG, NOT_A_BACKUP }

    /** [size] is the file's size when known (-1 otherwise); [head] its first bytes. */
    fun look(size: Long, head: ByteArray): Verdict = when {
        size > MAX_BYTES -> Verdict.TOO_BIG
        size == 0L || head.isEmpty() -> Verdict.EMPTY
        MAGIC.containsMatchIn(String(head, 0, minOf(head.size, HEAD_BYTES), Charsets.UTF_8)) -> Verdict.OK
        else -> Verdict.NOT_A_BACKUP
    }

    fun message(v: Verdict): String? = when (v) {
        Verdict.OK -> null
        Verdict.EMPTY -> "That file is empty."
        Verdict.TOO_BIG -> "That file is too big to be a Zyke backup."
        Verdict.NOT_A_BACKUP -> "This doesn't look like a Zyke backup."
    }
}

/** What to do when the app starts, decided before anything has had a chance to write. */
object FirstRunDecision {
    /** Welcome is shown at most this many times without an answer, so a problem in it can never trap the person. */
    const val MAX_SHOWS = 3

    enum class Action {
        /** Go straight to the app. */
        SKIP,
        /** Go straight to the app and remember that the question is settled. */
        SKIP_AND_MARK_DONE,
        /** Show the Welcome step. */
        WELCOME
    }

    /**
     * [autoBackupFileExists] only matters to what Welcome offers; a fresh install with or without a leftover file is
     * shown Welcome (it has Drive and file restore too). [previousShows] counts the times Welcome was already shown without an answer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decide(setupFilesExist: Boolean, markerDone: Boolean, autoBackupFileExists: Boolean, previousShows: Int = 0): Action = when {
        markerDone -> Action.SKIP
        setupFilesExist -> Action.SKIP_AND_MARK_DONE
        previousShows >= MAX_SHOWS -> Action.SKIP_AND_MARK_DONE
        else -> Action.WELCOME
    }
}
