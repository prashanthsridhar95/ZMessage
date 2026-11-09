package com.zyke.message.data.markets

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.flight.Airline
import com.zyke.message.domain.diag.LiveReply
import com.zyke.message.domain.diag.NetworkProblem
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

    /** The reply body for one flight number, or null if it could not be reached or read. */
    fun fetch(airline: Airline, number: String): String? = diagnose(airline, number)?.takeIf { it.statusCode in 200..299 }?.body

    /**
     * The same one request as [fetch], but with what came back whatever it was (status, content type, size, failure kind, body), for the
     * developer's live-status check and for telling "blocked" from "unreadable". Null only for an empty flight number.
     */
    fun diagnose(airline: Airline, number: String): LiveReply? {
        // Letters and digits only, from the airline table and the parsed number; encoded anyway.
        val query = (airline.code + number).filter { it.isLetterOrDigit() }.take(MAX_QUERY)
        if (query.isEmpty()) return null
        return request("https://api.flightradar24.com/common/v1/flight/list.json?query=${URLEncoder.encode(query, "UTF-8")}&fetchBy=flight&page=1&limit=25")
    }

    /** One request with its timing, retried once if it timed out. Never throws. */
    private fun request(address: String): LiveReply {
        val started = System.currentTimeMillis()
        val first = once(address, started)
        return if (first.network == NetworkProblem.TIMEOUT) once(address, System.currentTimeMillis()) else first
    }

    private fun once(address: String, started: Long): LiveReply = try {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A redirect could lead to another host; the one host is pinned.
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        }
        try {
            val code = connection.responseCode
            val type = connection.contentType?.take(MAX_TYPE)
            if (code !in 200..299) {
                DevLog.d("flight") { "FlightFetcher: HTTP $code" }
                // The error page, if any, is kept short: it often says why (a block page, a rate limit).
                val page = runCatching { connection.errorStream?.use { readBounded(it, MAX_ERROR_BYTES) } }.getOrNull()
                LiveReply(code, type, page?.let { String(it, Charsets.UTF_8) }, page?.size ?: 0, tookMs = System.currentTimeMillis() - started)
            } else {
                val bytes = connection.inputStream.use { readBounded(it, MAX_BYTES + 1) }
                val took = System.currentTimeMillis() - started
                if (bytes.size > MAX_BYTES) LiveReply(code, type, null, bytes.size, tooLarge = true, tookMs = took)
                else LiveReply(code, type, String(bytes, Charsets.UTF_8), bytes.size, tookMs = took)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        DevLog.d("flight") { "FlightFetcher: ${e.javaClass.simpleName}" }
        val problem = when (e) {
            is UnknownHostException -> NetworkProblem.DNS
            is SocketTimeoutException -> NetworkProblem.TIMEOUT
            is SSLException -> NetworkProblem.TLS
            is ConnectException, is NoRouteToHostException -> NetworkProblem.NO_NETWORK
            else -> NetworkProblem.OTHER
        }
        LiveReply(0, null, null, 0, network = problem, tookMs = System.currentTimeMillis() - started)
    }

    /** At most [limit] bytes by hand: InputStream.readNBytes(int) exists only from Android 13, and this app runs on 8 and up. */
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
        const val MAX_BYTES = 512 * 1024
        const val MAX_TYPE = 80
        const val MAX_ERROR_BYTES = 16 * 1024
        const val MAX_QUERY = 12
    }
}
