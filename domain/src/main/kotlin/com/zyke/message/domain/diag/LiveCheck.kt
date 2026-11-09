package com.zyke.message.domain.diag

import com.zyke.message.domain.backup.MiniJson
import com.zyke.message.domain.train.TrainApi

/** Why a request to a live-status service never produced an answer (nothing came back at all). */
enum class NetworkProblem { NONE, NO_NETWORK, DNS, TLS, TIMEOUT, OTHER }

/** Why a live-status reply could not be used, from the first thing that went wrong. [NONE] means it was readable. */
enum class LiveFailure {
    NONE, NO_NETWORK, DNS, TLS, TIMEOUT,
    /** 401, 403, 429 or 451: the service refused or rate-limited the request. */
    BLOCKED,
    /** Any other status outside 200..299, redirects included (redirects are never followed: the host is pinned). */
    HTTP_STATUS,
    EMPTY_BODY, TOO_LARGE,
    /** A 2xx reply that is not JSON (a web page, a captcha, plain text). */
    NOT_JSON,
    /** JSON, but not the shape the parser expects; the detail names the first missing field. */
    SHAPE_MISMATCH,
    /** Keyed services only (live train status): the service did not accept the person's key (HTTP 401 or 403, or its own message). */
    KEY_REJECTED,
    /** Keyed services only: the key's request limit was reached (HTTP 429, or the service's own message). */
    LIMIT_REACHED,
    /** Keyed services only: the service said no with a response code the app does not know. */
    PROVIDER,
    OTHER;

    /** The service answered with something, but not something the app can read or use (as opposed to not reaching it at all). */
    val isReplyProblem: Boolean get() = this == EMPTY_BODY || this == TOO_LARGE || this == NOT_JSON || this == SHAPE_MISMATCH
}

/** What one request to a live-status service came back with. Nothing here is personal: it is the public reply for one train or flight. */
data class LiveReply(
    /** The HTTP status, or 0 when none arrived. */
    val statusCode: Int,
    val contentType: String?,
    /** The body as text; null when there was none to read (an error status, a failure, or too large). */
    val body: String?,
    val bytes: Int,
    val network: NetworkProblem = NetworkProblem.NONE,
    val tooLarge: Boolean = false,
    val tookMs: Long = 0L
)

data class LiveDiagnosis(val reason: LiveFailure, /** The first missing field for [LiveFailure.SHAPE_MISMATCH], the status code for [LiveFailure.HTTP_STATUS] and [LiveFailure.BLOCKED]. */ val detail: String? = null) {
    val ok: Boolean get() = reason == LiveFailure.NONE
}

/** What the result card shows for one test. Plain data; [lines] is the text. */
data class LiveCheckReport(
    /** "Train 12633" or "Flight 6E2345". */
    val subject: String,
    val reply: LiveReply,
    val diagnosis: LiveDiagnosis,
    /** What the parser made of it: "found", "not found" or "unreadable"; null when there was nothing to parse. */
    val parsed: String?,
    /** The provider's own response code and message, when it sent them (developer tools only); never the key. */
    val provider: String? = null,
    /** The address that was asked, with the key left out; null for a service that takes no key. */
    val request: String? = null
) {
    fun lines(): List<String> = buildList {
        add(subject)
        add("HTTP status: " + if (reply.statusCode > 0) reply.statusCode.toString() else "none")
        add("Content type: ${reply.contentType ?: "none"}")
        add("Size: ${reply.bytes} bytes" + if (reply.tooLarge) " (over the limit)" else "")
        add("Took: ${reply.tookMs} ms")
        add("Parser: ${parsed ?: "not run"}")
        provider?.let { add("Provider: $it") }
        add("Result: " + LiveCheck.describe(diagnosis))
    }
}

/** The pure parts of the developer "Live status check": telling failures apart, wording them, and shaping the text to share. */
object LiveCheck {
    /** At most this many bytes of the raw reply are shared. */
    const val SHARE_MAX_BYTES = 64 * 1024
    const val TRUNCATED_NOTE = "\n[reply cut at 64 KB]\n"

    /**
     * Sorts one reply into the first thing that went wrong, or [LiveFailure.NONE]. [shapeMissing] is the first field the parser could
     * not find in a body that is JSON (null when nothing is missing or the body is not JSON); it is only read for a readable body.
     */
    fun classify(
        statusCode: Int, body: String?, network: NetworkProblem = NetworkProblem.NONE, tooLarge: Boolean = false, shapeMissing: String? = null,
        keyed: Boolean = false
    ): LiveDiagnosis {
        when (network) {
            NetworkProblem.NONE -> Unit
            NetworkProblem.NO_NETWORK -> return LiveDiagnosis(LiveFailure.NO_NETWORK)
            NetworkProblem.DNS -> return LiveDiagnosis(LiveFailure.DNS)
            NetworkProblem.TLS -> return LiveDiagnosis(LiveFailure.TLS)
            NetworkProblem.TIMEOUT -> return LiveDiagnosis(LiveFailure.TIMEOUT)
            NetworkProblem.OTHER -> return LiveDiagnosis(LiveFailure.OTHER)
        }
        if (keyed) {
            if (statusCode == 401 || statusCode == 403) return LiveDiagnosis(LiveFailure.KEY_REJECTED, "HTTP $statusCode")
            if (statusCode == 429) return LiveDiagnosis(LiveFailure.LIMIT_REACHED, "HTTP $statusCode")
        }
        if (statusCode in BLOCKED_CODES) return LiveDiagnosis(LiveFailure.BLOCKED, statusCode.toString())
        if (statusCode !in 200..299) return LiveDiagnosis(LiveFailure.HTTP_STATUS, statusCode.toString())
        if (tooLarge) return LiveDiagnosis(LiveFailure.TOO_LARGE)
        if (body == null || body.isBlank()) return LiveDiagnosis(LiveFailure.EMPTY_BODY)
        val json = runCatching { MiniJson.parse(body.trim()) }
        if (json.isFailure) return LiveDiagnosis(LiveFailure.NOT_JSON)
        val value = json.getOrNull()
        if (value !is Map<*, *> && value !is List<*>) return LiveDiagnosis(LiveFailure.SHAPE_MISMATCH, "object")
        if (shapeMissing != null) return LiveDiagnosis(LiveFailure.SHAPE_MISMATCH, shapeMissing)
        return LiveDiagnosis(LiveFailure.NONE)
    }

    /** Classifies a [LiveReply] directly. [keyed]: the service takes a personal key, so 401/403 mean the key and 429 the limit. */
    fun classify(reply: LiveReply, shapeMissing: String? = null, keyed: Boolean = false): LiveDiagnosis =
        classify(reply.statusCode, reply.body, reply.network, reply.tooLarge, shapeMissing, keyed)

    /** One line for the result card in the developer tools; specific, for the developer. */
    fun describe(d: LiveDiagnosis): String = when (d.reason) {
        LiveFailure.NONE -> "The reply was read"
        LiveFailure.NO_NETWORK -> "No network connection"
        LiveFailure.DNS -> "Could not find the server (no network or DNS failure)"
        LiveFailure.TLS -> "Secure connection (TLS) failed"
        LiveFailure.TIMEOUT -> "Timed out"
        LiveFailure.BLOCKED -> "The service refused the request (HTTP ${d.detail})"
        LiveFailure.HTTP_STATUS -> "Unexpected HTTP status ${d.detail}"
        LiveFailure.EMPTY_BODY -> "The reply was empty"
        LiveFailure.TOO_LARGE -> "The reply was larger than the limit"
        LiveFailure.NOT_JSON -> "The reply is not JSON"
        LiveFailure.SHAPE_MISMATCH -> "The reply is JSON but not the expected shape; first missing: ${d.detail}"
        LiveFailure.KEY_REJECTED -> "The key was rejected or is not allowed" + (d.detail?.let { " ($it)" } ?: "")
        LiveFailure.LIMIT_REACHED -> "The key's request limit was reached" + (d.detail?.let { " ($it)" } ?: "")
        LiveFailure.PROVIDER -> "The service declined with a response code the app does not know" + (d.detail?.let { " ($it)" } ?: "")
        LiveFailure.OTHER -> "The request failed"
    }

    /** What a train card says when the service answered but has no running status for that train on that day. */
    const val NO_STATUS_LINE = "No running status for this train on this day"
    const val KEY_REJECTED_LINE = "Train data key was rejected"
    const val LIMIT_LINE = "Daily limit reached"
    const val UNAVAILABLE_LINE = "Live status unavailable right now"

    /** The one calm line a card or sheet shows when the last try failed ([hasLast]: an older status is still shown below it). */
    fun cardLine(reason: LiveFailure?, hasLast: Boolean, noun: String): String = when {
        reason == LiveFailure.KEY_REJECTED -> KEY_REJECTED_LINE + if (hasLast) " · showing last known status" else ""
        reason == LiveFailure.LIMIT_REACHED -> LIMIT_LINE + if (hasLast) " · showing last known status" else ""
        reason == LiveFailure.PROVIDER -> UNAVAILABLE_LINE + if (hasLast) " · showing last known status" else ""
        reason == LiveFailure.TLS -> "Couldn’t make a secure connection" + if (hasLast) " · showing last known status" else " · showing your booking"
        reason == LiveFailure.BLOCKED -> "Live status is unavailable right now (service blocked the request)" + if (hasLast) " · showing last known status" else ""
        reason != null && reason.isReplyProblem -> "Couldn’t read the live reply" + if (hasLast) " · showing last known status" else " · showing your booking"
        hasLast -> "Couldn’t refresh · showing last known status"
        else -> "Couldn’t get live $noun · showing your booking"
    }

    /**
     * The text to share: a header saying what it is, then the raw body cut to [SHARE_MAX_BYTES]. Only the public reply for the sample or
     * tracked train or flight number is in it; nothing of the person's.
     */
    fun shareText(report: LiveCheckReport, body: String): String {
        val head = buildString {
            append("Zyke Message live status sample\n")
            append("Public status reply only; no messages, PNR or personal data.\n")
            append("Service for: ").append(report.subject).append('\n')
            report.request?.let { append("Request: ").append(it).append('\n') }
            report.lines().drop(1).forEach { append(it).append('\n') }
            append("\n===== raw reply =====\n")
        }
        // Whatever the key's place in an address, it is never in the shared text.
        return TrainApi.redact(head + truncate(body))
    }

    /** [text] cut so its UTF-8 form is at most [SHARE_MAX_BYTES], with a note when something was left out. */
    fun truncate(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= SHARE_MAX_BYTES) return text
        val cut = String(bytes, 0, SHARE_MAX_BYTES, Charsets.UTF_8).trimEnd('�')
        return cut + TRUNCATED_NOTE
    }

    private val BLOCKED_CODES = setOf(401, 403, 429, 451)
}
