package com.zyke.message.domain.diag

import com.zyke.message.domain.backup.MiniJson

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
    val parsed: String?
) {
    fun lines(): List<String> = buildList {
        add(subject)
        add("HTTP status: " + if (reply.statusCode > 0) reply.statusCode.toString() else "none")
        add("Content type: ${reply.contentType ?: "none"}")
        add("Size: ${reply.bytes} bytes" + if (reply.tooLarge) " (over the limit)" else "")
        add("Took: ${reply.tookMs} ms")
        add("Parser: ${parsed ?: "not run"}")
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
    fun classify(statusCode: Int, body: String?, network: NetworkProblem = NetworkProblem.NONE, tooLarge: Boolean = false, shapeMissing: String? = null): LiveDiagnosis {
        when (network) {
            NetworkProblem.NONE -> Unit
            NetworkProblem.NO_NETWORK -> return LiveDiagnosis(LiveFailure.NO_NETWORK)
            NetworkProblem.DNS -> return LiveDiagnosis(LiveFailure.DNS)
            NetworkProblem.TLS -> return LiveDiagnosis(LiveFailure.TLS)
            NetworkProblem.TIMEOUT -> return LiveDiagnosis(LiveFailure.TIMEOUT)
            NetworkProblem.OTHER -> return LiveDiagnosis(LiveFailure.OTHER)
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

    /** Classifies a [LiveReply] directly. */
    fun classify(reply: LiveReply, shapeMissing: String? = null): LiveDiagnosis =
        classify(reply.statusCode, reply.body, reply.network, reply.tooLarge, shapeMissing)

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
        LiveFailure.OTHER -> "The request failed"
    }

    /** The one calm line a card or sheet shows when the last try failed ([hasLast]: an older status is still shown below it). */
    fun cardLine(reason: LiveFailure?, hasLast: Boolean, noun: String): String = when {
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
            report.lines().drop(1).forEach { append(it).append('\n') }
            append("\n===== raw reply =====\n")
        }
        return head + truncate(body)
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
