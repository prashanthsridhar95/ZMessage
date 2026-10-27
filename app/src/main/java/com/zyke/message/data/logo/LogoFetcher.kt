package com.zyke.message.data.logo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only code in the app that talks to the internet, and the only thing it ever sends is the website
 * name of a well-known brand taken from [com.zyke.message.domain.classification.KnownEntityDomains]. No message text,
 * numbers or amounts. A test keeps network calls out of every other file.
 */
internal class LogoFetcher {

    /** The brand's icon, or null if no source had one. */
    fun fetch(domain: String): Bitmap? = SOURCES.firstNotNullOfOrNull { template -> download(template.replace("{domain}", domain)) }

    private fun download(address: String): Bitmap? = runCatching {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ZykeMessage")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val bytes = connection.inputStream.use { it.readBytes(MAX_BYTES) } ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= MIN_SIDE && it.height >= MIN_SIDE }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 6_000
        const val MAX_BYTES = 512 * 1024
        const val MIN_SIDE = 32
        const val BUFFER = 8 * 1024

        // Free favicon services, tried in order. Both are plain https GETs of one public site name.
        val SOURCES = listOf(
            "https://www.google.com/s2/favicons?domain={domain}&sz=128",
            "https://icons.duckduckgo.com/ip3/{domain}.ico"
        )
    }
}
