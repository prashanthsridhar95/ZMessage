package com.zyke.message.domain.diag

/** One address the developer's "Probe train sources" tries for the sample train. Plain data; the list itself lives in the network class. */
data class ProbeCandidate(val label: String, val url: String) {
    /** The host part of [url], such as "www.ixigo.com". */
    val host: String get() = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
}

/** What one probe request came back with. Nothing here is personal: it is the public reply for the sample train. */
data class ProbeResult(
    /** 1-based position in the candidate list. */
    val index: Int,
    val label: String,
    val url: String,
    val host: String,
    /** The HTTP status, or 0 when none arrived. */
    val statusCode: Int,
    val network: NetworkProblem = NetworkProblem.NONE,
    val contentType: String? = null,
    /** Bytes of the body that were read (at most [TrainProbeFormat.BODY_READ_MAX] of them). */
    val bytes: Int = 0,
    /** The body was longer than what was read. */
    val cut: Boolean = false,
    val body: String? = null,
    /** The Location header of a redirect, as sent (redirects are never followed). */
    val location: String? = null,
    val tookMs: Long = 0L
)

/** The pure parts of "Probe train sources": one-line wording for the result list and the text to share. */
object TrainProbeFormat {
    /** How many characters of the body the result list shows. */
    const val PREVIEW_CHARS = 160

    /** At most this many bytes of each body are read, shown or shared. */
    const val BODY_READ_MAX = 16 * 1024
    const val CUT_NOTE = "\n[body cut at 16 KB]"
    private const val LOCATION_CHARS = 120
    private const val TYPE_CHARS = 60

    /** [text] on one line: control characters and runs of white space become one space, then cut to [max] characters with an ellipsis. */
    fun singleLine(text: String?, max: Int): String {
        if (text == null) return ""
        val flat = StringBuilder(minOf(text.length, max + 8))
        var space = false
        for (ch in text) {
            if (ch.isISOControl() || ch.isWhitespace()) {
                space = true
            } else {
                if (space && flat.isNotEmpty()) flat.append(' ')
                space = false
                flat.append(ch)
            }
            if (flat.length > max + 1) break
        }
        if (flat.length <= max) return flat.toString()
        var end = max
        if (end > 0 && flat[end - 1].isHighSurrogate()) end--
        return flat.substring(0, end).trimEnd() + "…"
    }

    /** Short outcome: "HTTP 200", "DNS failure", "timeout", "blocked (HTTP 403)", "HTTP 301 to https://host/path". */
    fun outcome(r: ProbeResult): String = when (r.network) {
        NetworkProblem.DNS -> "DNS failure"
        NetworkProblem.TLS -> "TLS failure"
        NetworkProblem.TIMEOUT -> "timeout"
        NetworkProblem.NO_NETWORK -> "no connection"
        NetworkProblem.OTHER -> "other failure"
        NetworkProblem.NONE -> when (r.statusCode) {
            401, 403, 429, 451 -> "blocked (HTTP ${r.statusCode})"
            in 300..399 -> {
                val where = singleLine(r.location, LOCATION_CHARS)
                if (where.isEmpty()) "HTTP ${r.statusCode} (no Location)" else "HTTP ${r.statusCode} to $where"
            }
            0 -> "other failure"
            else -> "HTTP ${r.statusCode}"
        }
    }

    fun sizeText(r: ProbeResult): String = if (r.cut) "${r.bytes} B+ (cut)" else "${r.bytes} B"

    /** The one line of facts under a candidate: outcome, content type, size, time. */
    fun facts(r: ProbeResult): String = buildList {
        add(outcome(r))
        if (r.network == NetworkProblem.NONE) {
            add(singleLine(r.contentType, TYPE_CHARS).ifEmpty { "no content type" })
            add(sizeText(r))
        }
        add("${r.tookMs} ms")
    }.joinToString(" · ")

    /** The first [PREVIEW_CHARS] characters of the body on one line, or a note when there is none. */
    fun preview(r: ProbeResult): String = singleLine(r.body, PREVIEW_CHARS).ifEmpty { "(no body)" }

    /** The three lines the result list shows per candidate: title, facts, preview. */
    fun resultLines(r: ProbeResult): List<String> = listOf("${r.index}. ${r.label}", "${r.host} · ${facts(r)}", preview(r))

    /** [body] cut so its UTF-8 form is at most [BODY_READ_MAX] bytes, with a note when something was left out. */
    fun capBody(body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size <= BODY_READ_MAX) return body
        return String(bytes, 0, BODY_READ_MAX, Charsets.UTF_8).trimEnd('�') + CUT_NOTE
    }

    /** All collected replies as one text to share, each body capped and labelled by candidate. */
    fun shareText(results: List<ProbeResult>, sampleTrain: String): String = buildString {
        append("Zyke Message train source probe\n")
        append("Public data only; the only thing sent was the sample train number ").append(sampleTrain).append(".\n")
        append("Redirects are not followed; a 3xx shows its Location.\n\n")
        results.forEach { r ->
            append("===== ").append(r.index).append(". ").append(r.label).append(" (").append(r.host).append(") =====\n")
            append("URL: ").append(r.url).append('\n')
            append("Result: ").append(facts(r)).append('\n')
            val location = r.location
            if (location != null) append("Location: ").append(singleLine(location, 300)).append('\n')
            append("--- body ---\n")
            val body = r.body
            append(if (body.isNullOrEmpty()) "(no body)" else capBody(body)).append("\n\n")
        }
    }
}
