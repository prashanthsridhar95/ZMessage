package com.zyke.message.data.markets

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.diag.LiveReply
import com.zyke.message.domain.diag.NetworkProblem
import com.zyke.message.domain.train.TrainApi
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.LocalDate
import javax.net.ssl.SSLException

/**
 * The fourth code in the app that talks to the internet for information (after the market strip, the logo fetch and the flight
 * fetch, besides the person's own Drive backup). It exists for live train running status, which is off until the person has saved
 * their own train data key and switched it on in Settings. For one train it sends the five-digit train number (such as "12633"), the
 * day that run started (yyyymmdd) and the person's key to Live Train Status, Indian Rail API, over HTTPS, and nothing else. No message
 * text, PNR, station, name, seat, amount or device identifier. A test keeps network calls out of every other file and pins the host.
 *
 * The key sits in the address (that is how the provider takes it), so it is never logged, never put in an exception text, and
 * removed from anything shown or shared ([TrainApi.redact]). Only HTTPS is used: the provider's documentation shows a plain-http address, but the
 * key is not sent in clear text, and there is no fallback; if the secure connection fails the card says so.
 *
 * UNVERIFIED AGAINST THE LIVE SERVICE: the address and the reply format (see IndianRailApiParser) come from the provider's
 * documentation page (its example is dated 2018), and the build environment cannot reach the host. It may differ, change, rate-limit
 * or block, so every call fails soft: a bad status, a redirect, a timeout or a broken reply is a [LiveReply] describing it, never an
 * exception, and the app keeps the schedule from the person's messages. Logs hold status codes only.
 */
internal class TrainFetcher {

    /**
     * One request for the run of [number] that started on [startDate], with what came back whatever it was (status, content type, size,
     * failure kind, body). Null only for a number that is not five digits or a key that cannot be one. [onSend] is told before each
     * request that goes out (a timed-out one is tried once more), so the day's request count is true.
     */
    fun diagnose(number: String, startDate: LocalDate, key: String, onSend: () -> Unit = {}): LiveReply? {
        // Five digits from the booking or the person's typing; encoded anyway.
        val digits = number.filter { it in '0'..'9' }.take(5)
        if (digits.length != 5) return null
        if (!TrainApi.isValidKey(key)) return null
        val safeKey = URLEncoder.encode(TrainApi.cleanKey(key), "UTF-8").replace("+", "%20")
        val date = TrainApi.dateParam(startDate)
        return request("https://indianrailapi.com/api/v2/livetrainstatus/apikey/$safeKey/trainnumber/$digits/date/$date/", onSend)
    }

    /** One request with its timing, retried once if it timed out. Never throws. */
    private fun request(address: String, onSend: () -> Unit): LiveReply {
        val started = System.currentTimeMillis()
        val first = once(address, started, onSend)
        return if (first.network == NetworkProblem.TIMEOUT) once(address, System.currentTimeMillis(), onSend) else first
    }

    private fun once(address: String, started: Long, onSend: () -> Unit): LiveReply = try {
        onSend()
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A redirect could lead to another host, or to clear text; the one host is pinned.
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
                DevLog.d("train") { "TrainFetcher: HTTP $code" }
                // The error page, if any, is kept short: it often says why (a rejected key, a limit).
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
        // The class name only: an exception's text can carry the address, and so the key.
        DevLog.d("train") { "TrainFetcher: ${e.javaClass.simpleName}" }
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
    }
}
