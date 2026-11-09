package com.zyke.message.data.markets

import com.zyke.message.data.diag.DevLog
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The fourth code in the app that talks to the internet for information (after the market strip, the logo fetch and the flight
 * fetch, besides the person's own Drive backup). It exists for live train running status, which is off until the person switches it
 * on in Settings. For one train it sends only the five-digit train number, such as "12633", and how many days ago that run started (0
 * for a train that started today): the train number and the start day, nothing else. No message text, PNR, station, name, seat, amount
 * or device identifier. A test keeps network calls out of every other file and pins the host.
 *
 * UNVERIFIED AGAINST ANY LIVE SERVICE: Indian Railways has no official key-less running-status API, and every host that could carry one
 * was refused by the build environment's proxy, so the host, the path and the reply format (see TrainStatusParser) are the best-known
 * ones of an unofficial service, written from memory. It may differ, change, rate-limit or block, so every call fails soft: a bad
 * status, a redirect, a timeout or a broken reply gives null, never an exception, and the app keeps the schedule from the person's
 * messages. Logs hold status codes only.
 */
internal class TrainFetcher {

    /** The reply for one train's run that started [startDaysAgo] days ago (0 = today), or null if it could not be reached. */
    fun fetch(number: String, startDaysAgo: Int): String? {
        // Five digits from the booking or the person's typing; encoded anyway.
        val digits = number.filter { it in '0'..'9' }.take(5)
        if (digits.length != 5) return null
        val days = startDaysAgo.coerceIn(0, MAX_DAYS)
        return get("https://api.confirmtkt.com/api/trains/runningstatus?trainno=${URLEncoder.encode(digits, "UTF-8")}&startday=$days")
    }

    private fun get(address: String): String? = try {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A redirect could lead to another host; the one host is pinned.
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) DevLog.d("train") { "TrainFetcher: HTTP $code" }
            if (code !in 200..299) null
            else connection.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        DevLog.d("train") { "TrainFetcher: ${e.javaClass.simpleName}" }
        null
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_BYTES = 512 * 1024
        const val MAX_DAYS = 6
    }
}
