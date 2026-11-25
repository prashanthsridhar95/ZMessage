package com.zyke.message.data.maps

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.legal.LegalInfo
import com.zyke.message.domain.places.TileId
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only code that fetches map pictures, and the only network code of the Spending map. One host, `tile.openstreetmap.org`,
 * over HTTPS; the only thing sent is the tile's zoom, column and row (what part of the world is on screen) and a User-Agent that names
 * the app, as the OpenStreetMap tile usage policy asks. No cookies, no redirects, no identifiers, 5 s timeouts, 256 KB cap. It is
 * called only from [TileSource], which is used only while the map page is open, for the tiles on screen, two at a time.
 * Every failure gives null (a blank square), never an exception; the log holds status codes and exception class names only.
 *
 * NOT EXERCISED AGAINST THE LIVE SERVER: the build environment cannot reach it. The URL scheme (/{z}/{x}/{y}.png) is the
 * documented one.
 */
internal class TileFetcher {

    /** The PNG bytes of one tile, or null. */
    fun fetch(tile: TileId): ByteArray? {
        if (tile.z !in 0..19 || tile.x < 0 || tile.y < 0 || tile.x >= (1 shl tile.z) || tile.y >= (1 shl tile.z)) return null
        return try {
            val connection = (URL("https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // A redirect could lead to another host; the one host is pinned.
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "${LegalInfo.APP_NAME}/1.0 (Android; private on-device spending map; contact ${LegalInfo.CONTACT_EMAIL})")
                setRequestProperty("Accept", "image/png")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) { DevLog.d("maps") { "TileFetcher: HTTP $code" }; null }
                else connection.inputStream.use { readBounded(it, MAX_BYTES + 1) }.takeIf { it.size in 1..MAX_BYTES }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            DevLog.d("maps") { "TileFetcher: ${e.javaClass.simpleName}" }
            null
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_BYTES = 256 * 1024
    }
}
