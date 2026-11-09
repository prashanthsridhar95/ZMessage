package com.zyke.message.data.markets

import com.zyke.message.domain.diag.NetworkProblem
import com.zyke.message.domain.diag.ProbeCandidate
import com.zyke.message.domain.diag.ProbeResult
import com.zyke.message.domain.diag.TrainProbeFormat
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * DEVELOPER PROBE ONLY. The only other network class besides [TrainFetcher] for trains. It exists to find a working live train status
 * source: the build environment cannot reach any outside host and [TrainFetcher]'s host did not resolve on a real phone, so the
 * developer's "Probe train sources" (Settings, Live status check, developer options on, never in Demo mode) tries the short list below
 * one after another and shows what each said. Nothing is sent unless the developer taps Probe, and only the sample train number goes
 * out (plus, for one address, today's date). The normal feature ([com.zyke.message.data.trains.TrainService]) never calls this class.
 *
 * The list is for finding a working source and will be cut down to the winner, after which this class goes away. All of it is
 * UNVERIFIED (from memory); HTML pages are not parsed, just reported. One request per address, no retries, no redirects followed (a
 * 3xx reports its Location), no cookies, a 6 s timeout, and a hard stop per address so the whole run stays under a minute.
 */
internal class TrainProbe {

    /** The addresses to try for [number] (five digits), or null for anything else. */
    fun candidates(number: String, today: LocalDate = LocalDate.now()): List<ProbeCandidate>? {
        val train = number.filter { it in '0'..'9' }
        if (train.length != 5 || train != number) return null
        val date = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        return listOf(
            ProbeCandidate("ConfirmTkt API (current)", "https://api.confirmtkt.com/api/trains/runningstatus?trainno=$train&startday=0"),
            // api.confirmtkt.com is a real host that answered 404 "No HTTP resource was found" for the first path on a real phone, so
            // the host is right and the path is not: these are other paths its API is believed to use (same host, nothing new pinned).
            ProbeCandidate("ConfirmTkt API path 2", "https://api.confirmtkt.com/api/platform/trainsrunningstatus?trainno=$train&startday=0"),
            ProbeCandidate("ConfirmTkt API path 3", "https://api.confirmtkt.com/api/platform/runningstatus?trainno=$train&startday=0"),
            ProbeCandidate("ConfirmTkt API path 4", "https://api.confirmtkt.com/api/trains/livestatus?trainno=$train&startday=0"),
            ProbeCandidate("ConfirmTkt API path 5", "https://api.confirmtkt.com/api/v1/trains/runningstatus?trainno=$train&startday=0"),
            ProbeCandidate("ConfirmTkt API path 6", "https://api.confirmtkt.com/api/trainsrunningstatus?trainno=$train&startday=0"),
            ProbeCandidate("ConfirmTkt API root", "https://api.confirmtkt.com/"),
            ProbeCandidate("ConfirmTkt www", "https://www.confirmtkt.com/api/trains/runningstatus?trainno=$train&startday=0"),
            ProbeCandidate("RailYatri page", "https://www.railyatri.in/live-train-status/$train?start_date=$date"),
            ProbeCandidate("RailYatri API (guess)", "https://www.railyatri.in/api/trains/live-train-status/$train"),
            ProbeCandidate("ixigo page", "https://www.ixigo.com/trains/live-train-status/$train"),
            ProbeCandidate("NTES (reachability)", "https://enquiry.indianrail.gov.in/mntes/"),
            ProbeCandidate("Trainman page", "https://www.trainman.in/running-status/$train"),
            ProbeCandidate("runningstatus.in (guess)", "https://runningstatus.in/status/$train")
        )
    }

    /**
     * One request to [candidate] ([index] is its 1-based place in the list), blocking, at most [HARD_LIMIT_MS]. Never throws. A request
     * that is still stuck at the limit (a slow name lookup does not honour the timeouts) is reported as a timeout.
     */
    fun probe(index: Int, candidate: ProbeCandidate): ProbeResult {
        val started = System.currentTimeMillis()
        val done = AtomicReference<ProbeResult?>(null)
        val open = AtomicReference<HttpURLConnection?>(null)
        val worker = Thread({ done.set(once(index, candidate, started, open)) }, "train-probe").apply { isDaemon = true }
        worker.start()
        try {
            worker.join(HARD_LIMIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        done.get()?.let { return it }
        runCatching { open.get()?.disconnect() }
        return failure(index, candidate, NetworkProblem.TIMEOUT, System.currentTimeMillis() - started)
    }

    private fun failure(index: Int, c: ProbeCandidate, problem: NetworkProblem, took: Long) =
        ProbeResult(index, c.label, c.url, c.host, 0, network = problem, tookMs = took)

    private fun once(index: Int, c: ProbeCandidate, started: Long, open: AtomicReference<HttpURLConnection?>): ProbeResult = try {
        val connection = (URL(c.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // A redirect could lead to another host; none is followed, the Location is reported instead.
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json, text/html, text/plain, */*")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        }
        open.set(connection)
        try {
            val code = connection.responseCode
            val type = connection.contentType?.take(MAX_TYPE)
            val location = connection.getHeaderField("Location")?.take(MAX_LOCATION)
            val stream = runCatching { if (code >= 400) connection.errorStream else connection.inputStream }.getOrNull()
            val bytes = runCatching { stream?.use { readBounded(it, TrainProbeFormat.BODY_READ_MAX + 1) } }.getOrNull()
            val cut = bytes != null && bytes.size > TrainProbeFormat.BODY_READ_MAX
            val kept = if (bytes == null) null else if (cut) bytes.copyOf(TrainProbeFormat.BODY_READ_MAX) else bytes
            ProbeResult(
                index, c.label, c.url, c.host, code, NetworkProblem.NONE, type, kept?.size ?: 0, cut,
                kept?.let { String(it, Charsets.UTF_8) }, location, System.currentTimeMillis() - started
            )
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        val problem = when (e) {
            is UnknownHostException -> NetworkProblem.DNS
            is SocketTimeoutException -> NetworkProblem.TIMEOUT
            is SSLException -> NetworkProblem.TLS
            is ConnectException, is NoRouteToHostException -> NetworkProblem.NO_NETWORK
            else -> NetworkProblem.OTHER
        }
        failure(index, c, problem, System.currentTimeMillis() - started)
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
        const val TIMEOUT_MS = 6_000

        /** Eight addresses at most 7 s each is under a minute. */
        const val HARD_LIMIT_MS = 7_000L
        const val MAX_TYPE = 80
        const val MAX_LOCATION = 300
    }
}
