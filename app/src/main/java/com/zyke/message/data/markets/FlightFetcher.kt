package com.zyke.message.data.markets

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.flight.Airline
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The third code in the app that talks to the internet for information (after the market strip and the logo fetch, besides
 * the person's own Drive backup). It exists for live flight status, which is off until the person switches it on in
 * Settings. For one flight it sends only the airline code and flight number, such as "6E2345", to Flightradar24's public
 * flight-history endpoint; the day of travel is used on the phone to pick the right leg from the reply and is not sent at
 * all. No message text, PNR, name, amount or device identifier. A test keeps network calls out of every other file and pins the host.
 *
 * UNVERIFIED AGAINST THE LIVE SERVICE: the endpoint is unofficial and the build environment could not reach it, so the reply
 * format is the known one (see FlightStatusParser). It may change, rate-limit or block, so every call fails soft: a bad
 * status, a redirect, a timeout or a broken reply gives null, never an exception. Logs hold status codes only.
 */
internal class FlightFetcher {

    /** The reply for one flight number, or null if it could not be reached. */
    fun fetch(airline: Airline, number: String): String? {
        // Letters and digits only, from the airline table and the parsed number; encoded anyway.
        val query = (airline.code + number).filter { it.isLetterOrDigit() }.take(MAX_QUERY)
        if (query.isEmpty()) return null
        return get("https://api.flightradar24.com/common/v1/flight/list.json?query=${URLEncoder.encode(query, "UTF-8")}&fetchBy=flight&page=1&limit=25")
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
            if (code !in 200..299) DevLog.d("flight") { "FlightFetcher: HTTP $code" }
            if (code !in 200..299) null
            else connection.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        DevLog.d("flight") { "FlightFetcher: ${e.javaClass.simpleName}" }
        null
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_BYTES = 512 * 1024
        const val MAX_QUERY = 12
    }
}
