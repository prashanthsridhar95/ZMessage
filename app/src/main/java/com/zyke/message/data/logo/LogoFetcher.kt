package com.zyke.message.data.logo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only code in the app that talks to the internet, and the only thing it ever sends is the website
 * name of a well-known brand taken from [com.zyke.message.domain.classification.KnownEntityDomains]. No message text,
 * numbers or amounts. A test keeps network calls out of every other file. It also downloads a picture the person picked
 * on a Google image search they opened themselves ([fetchPicked]); the address is the one they tapped. And it downloads a logo from
 * the web address the bundled logo list gives for a brand ([fetchListed], see [com.zyke.message.domain.logo.LogoList]): the request
 * is the picture's address and nothing else.
 */
internal class LogoFetcher {

    /** The brand's icon, or null if no source had one. */
    fun fetch(domain: String): Bitmap? = SOURCES.firstNotNullOfOrNull { template -> download(template.replace("{domain}", domain)) }

    /** A picture the person chose on a web page: any https address, since they picked it, within a size limit. */
    fun fetchPicked(address: String): Bitmap? = if (address.startsWith("https://", ignoreCase = true)) download(address, PICKED_MAX_BYTES) else null

    /**
     * A logo from the bundled list's address, with stricter rules than the rest: https only, no cookie and no referrer sent, a redirect is
     * followed only when it stays on the same host and https (at most [MAX_REDIRECTS]), nothing larger than [LISTED_MAX_BYTES] is read,
     * and a picture with an absurd pixel count is not decoded. Anything else gives null.
     */
    fun fetchListed(address: String): Bitmap? = runCatching {
        val first = URL(address)
        if (!first.protocol.equals("https", ignoreCase = true) || first.userInfo != null) null else followListed(first, first.host.lowercase())
    }.getOrNull()

    private fun followListed(start: URL, host: String): Bitmap? {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "ZykeMessage")
            }
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val next = connection.getHeaderField("Location")?.let { URL(current, it) } ?: return null
                    if (!next.protocol.equals("https", ignoreCase = true) || !next.host.equals(host, ignoreCase = true) || next.userInfo != null || (next.port != -1 && next.port != HTTPS_PORT)) return null
                    current = next
                } else {
                    if (code != HttpURLConnection.HTTP_OK) return null
                    val bytes = connection.inputStream.use { it.readBytes(LISTED_MAX_BYTES) } ?: return null
                    return decodeBounded(bytes)
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_SIDE || bounds.outHeight < MIN_SIDE || bounds.outWidth > LISTED_MAX_SIDE || bounds.outHeight > LISTED_MAX_SIDE) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun download(address: String, limit: Int = MAX_BYTES): Bitmap? = runCatching {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ZykeMessage")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val bytes = connection.inputStream.use { it.readBytes(limit) } ?: return@runCatching null
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
        const val PICKED_MAX_BYTES = 4 * 1024 * 1024
        const val LISTED_MAX_BYTES = 512 * 1024
        const val LISTED_MAX_SIDE = 4096
        const val MAX_REDIRECTS = 2
        const val HTTPS_PORT = 443
        val REDIRECT_CODES = 301..308
        const val MIN_SIDE = 48
        const val BUFFER = 8 * 1024

        // Tried in order, sharpest first. Each is a plain https GET for one public site name.
        val SOURCES = listOf(
            "https://{domain}/apple-touch-icon.png",
            "https://www.google.com/s2/favicons?domain={domain}&sz=128",
            "https://icons.duckduckgo.com/ip3/{domain}.ico"
        )
    }
}
