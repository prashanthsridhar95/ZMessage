package com.zyke.message.data.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.zyke.message.domain.diag.LogBundle
import com.zyke.message.domain.diag.LogSection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds one text file from the black box and the developer log and offers it to the app the person picks.
 *
 * Deliberately independent of the app's container, view models, preferences and Compose: it is what "Share logs" on
 * the launcher icon runs, and that has to work when the app itself crashes on launch. It reads only the log files,
 * writes one file into the private cache and never throws. It does not send anything itself.
 */
object LogShare {
    private const val SHARED_DIR = "shared_logs"
    private const val LOG_DIR = "dev_logs"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    enum class Result { SHARED, NOTHING_YET, FAILED }

    /** Oldest first; the black box last so the crash is at the end and survives the size cap. */
    private val files = listOf("zyke.1.log", "zyke.log", BlackBox.PREVIOUS, BlackBox.FILE)

    fun hasLogs(context: Context): Boolean = runCatching {
        val dir = File(context.applicationContext.filesDir, LOG_DIR)
        files.any { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }.getOrDefault(false)

    fun share(context: Context): Result {
        return try {
            val app = context.applicationContext
            val dir = File(app.filesDir, LOG_DIR)
            val sections = files.map { name -> LogSection(name, runCatching { File(dir, name).takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")) }
            if (!LogBundle.hasContent(sections)) return Result.NOTHING_YET

            val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
            val header = listOf(
                "App: ${info?.versionName} (${info?.longVersionCode})",
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL}",
                "Made: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}",
                "Contains: start-up and crash record (class names, line numbers, ids); never message text, amounts or names."
            )
            val text = LogBundle.build(header, sections)

            val name = "zyke-log-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"
            val out = File(app.cacheDir, SHARED_DIR).apply { mkdirs() }
            out.listFiles()?.forEach { it.delete() }
            val file = File(out, name).apply { writeText(text) }
            val uri = FileProvider.getUriForFile(app, app.packageName + AUTHORITY_SUFFIX, file)

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Zyke Message log")
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.SHARED
        } catch (_: Throwable) {
            Result.FAILED
        }
    }
}
