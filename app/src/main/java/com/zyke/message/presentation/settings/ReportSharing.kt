package com.zyke.message.presentation.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes the parsing report to a text file in the app's private cache and offers it to whichever app the
 * user picks. The file is only readable by the app that is chosen, only for this share, and old reports
 * are cleaned up the next time one is made. Nothing is sent anywhere by the app itself.
 */
object ReportSharing {
    private const val DIRECTORY = "reports"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun share(context: Context, fileName: String, text: String) {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }

        val file = File(directory, fileName).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Zyke Message parsing report")
            // Grants the chosen app read access to this one file (including through the chooser).
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share parsing report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
