package com.zyke.message.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The plain sentences the backup screen shows about when. Pure, so the wording is tested. Never includes accounts or names. */
object BackupText {
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** "Last backup: today 02:03 · 312 KB · Google Drive". */
    fun lastBackup(at: Instant, now: Instant, zone: ZoneId, sizeBytes: Long, where: String, locale: Locale = Locale.getDefault()): String =
        lastLine("Last backup", at, now, zone, sizeBytes, where, locale)

    /** "Last saved to folder: today 02:03 · 312 KB · Downloads/Zyke". */
    fun lastSavedToFolder(at: Instant, now: Instant, zone: ZoneId, sizeBytes: Long, folder: String, locale: Locale = Locale.getDefault()): String =
        lastLine("Last saved to folder", at, now, zone, sizeBytes, folder, locale)

    private fun lastLine(label: String, at: Instant, now: Instant, zone: ZoneId, sizeBytes: Long, where: String, locale: Locale): String {
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val dayText = when (day) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(at.atZone(zone))
        }
        val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(at.atZone(zone))
        return "$label: $dayText $time · ${size(sizeBytes)} · $where"
    }

    /** "Next backup: Sun 2 Nov, 2:00 am", or "as soon as Android allows" when it is already due. */
    fun nextBackup(next: Instant, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        if (!next.isAfter(now)) return "Next backup: as soon as Android allows"
        val text = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale).format(next.atZone(zone))
            .replace("AM", "am").replace("PM", "pm")
        return "Next backup: $text"
    }
}

/** One backup file held in the person's Google Drive private app folder. */
data class RemoteBackup(val id: String, val modifiedAt: Instant, val sizeBytes: Long)

/** How many backups Drive keeps, and which to delete. */
object DriveRetention {
    const val KEEP = 7

    /** The ones beyond the newest [keep] (newest by modified time). */
    fun toDelete(all: List<RemoteBackup>, keep: Int = KEEP): List<RemoteBackup> =
        all.sortedByDescending { it.modifiedAt }.drop(keep)
}
