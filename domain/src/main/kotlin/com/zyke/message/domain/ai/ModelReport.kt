package com.zyke.message.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone's AI service last said when asked, as one word. Never carries any text of a message. */
enum class PhoneModelStatus(val word: String) {
    UNKNOWN("not asked yet"),
    READY("ready"),
    NOT_DOWNLOADED("not downloaded"),
    DOWNLOADING("downloading"),
    UNSUPPORTED("not supported"),
    ERROR("error")
}

/** The kind of the last event on our own download stream (not its content). */
enum class DownloadEvent(val word: String) {
    NONE("none"),
    PROGRESS("progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    ERROR("error"),
    OTHER("other")
}

/**
 * The raw facts about the model, kept beside [AiAvailability] so the page can always say what is true.
 * [streamActive] is true only while this app itself is collecting a download. [megabytes] is set only once bytes arrived.
 * Times are epoch milliseconds from the caller's clock.
 */
data class ModelReport(
    val phoneStatus: PhoneModelStatus = PhoneModelStatus.UNKNOWN,
    val lastEvent: DownloadEvent = DownloadEvent.NONE,
    val checkedAtMs: Long? = null,
    val streamActive: Boolean = false,
    val megabytes: Int? = null,
    val lastProgressAtMs: Long? = null,
    /** When this phone last gave this app a real answer (persisted by the app), and how long that answer took. Evidence beats the status word. */
    val workedAtMs: Long? = null,
    val workedLatencyMs: Long? = null,
    /** When our own download stream started, for the stream-less cap. */
    val streamStartedAtMs: Long? = null,
    /** We asked for the download, nothing came back, and the phone still says not downloaded. */
    val askedNotStarted: Boolean = false,
    /** A request is in flight and no request has finished yet in this app start (the model is waking up). */
    val warmingUp: Boolean = false,
    /** At least one request finished in this app start. */
    val warmedUp: Boolean = false
) {
    /** The part of the diagnostics line that comes from the phone: status word and last download event kind. */
    fun line(): String = "phone says: ${phoneStatus.word} · download event: ${lastEvent.word}"
}

/** The shape every engine without a report (a test, Demo) shares. */
internal val NoModelReport: StateFlow<ModelReport> = MutableStateFlow(ModelReport())

/** The rules for the model card, kept here so they can be tested without a phone. */
object ModelWatch {
    /** How often the open page asks the phone again while the model is downloading. */
    const val POLL_MS = 5_000L

    /** Google's service reports a download but this app is not following one: it may be queued or paused on its side. */
    fun waitingOnGoogle(availability: AiAvailability, report: ModelReport): Boolean =
        availability is AiAvailability.Downloading && !report.streamActive

    /**
     * Whether "Download model" can be tapped. Never off while nothing of ours is running: with Google's service
     * reporting a download, tapping it attaches to (or restarts) the download.
     */
    fun canDownload(availability: AiAvailability, report: ModelReport): Boolean =
        (availability == AiAvailability.NeedsDownload || availability is AiAvailability.Failed || availability is AiAvailability.Downloading) &&
            !report.streamActive

    /** Whether the open page should keep asking the phone. */
    fun shouldPoll(availability: AiAvailability): Boolean = availability is AiAvailability.Downloading

    /** The headline detail for the Downloading state. */
    fun downloadingDetail(report: ModelReport): String = when {
        report.megabytes != null -> "${report.megabytes} MB downloaded so far. You can leave this page."
        report.streamActive -> "Asked Google's service for the model. No progress is reported by the phone yet. " + WAIT_HINT
        else -> "Waiting for Google's service. No progress is reported by the phone yet. " + WAIT_HINT
    }

    /** "Google's AI service reports: downloading". The time of the check is added by the screen. */
    fun serviceLine(report: ModelReport): String = "Google's AI service reports: ${report.phoneStatus.word}"

    /** The state after a request to the model worked while the status said otherwise: it is ready. */
    fun afterSuccessfulAnswer(availability: AiAvailability): AiAvailability =
        if (availability is AiAvailability.Busy) availability else AiAvailability.Ready

    /** How long a success counts as proof that the model is there. */
    const val RECENT_MS = 14L * 24 * 60 * 60 * 1000

    /** A download with no event and no byte for this long is not "Downloading" any more: the phone's own status is shown instead. */
    const val STREAMLESS_CAP_MS = 30_000L

    /** At most one warm-up try in this long. */
    const val WARMUP_GAP_MS = 10L * 60 * 1000

    const val NOT_STARTED_DETAIL = "Asked, but the phone has not started. Try 'Try it' to see if it works anyway."

    /** Whether a real answer came recently enough to trust over the phone's status word. */
    fun worked(report: ModelReport, nowMs: Long): Boolean = report.workedAtMs?.let { nowMs - it <= RECENT_MS } == true

    /**
     * What to show and let features use: evidence of use beats the status word. A recent answer makes the model Ready whatever
     * the status said (not downloaded, not supported, an error); only a download this app or Google's service really reports
     * keeps its own state.
     */
    fun effective(availability: AiAvailability, report: ModelReport, nowMs: Long): AiAvailability =
        if (availability !is AiAvailability.Downloading && availability != AiAvailability.Busy && worked(report, nowMs)) AiAvailability.Ready
        else availability

    /** True when our own download stream has shown nothing at all for [STREAMLESS_CAP_MS]. */
    fun streamlessExpired(report: ModelReport, nowMs: Long): Boolean =
        report.streamActive && report.lastEvent == DownloadEvent.NONE && report.streamStartedAtMs?.let { nowMs - it >= STREAMLESS_CAP_MS } == true

    /** "Working on this phone · last answer 7.0 s · 3:42". [at] is the clock text of the answer. */
    fun workingLine(report: ModelReport, at: String): String =
        "Working on this phone" + (report.workedLatencyMs?.let { " · last answer %.1f s".format(it / MS_PER_S) } ?: "") + " · $at"

    /** The quiet note when the phone's word and the evidence disagree, else null. */
    fun disagreement(report: ModelReport, at: String, nowMs: Long): String? =
        if (worked(report, nowMs) && (report.phoneStatus == PhoneModelStatus.NOT_DOWNLOADED || report.phoneStatus == PhoneModelStatus.UNSUPPORTED)) {
            "Android reports the model as ${report.phoneStatus.word}, but it answered at $at."
        } else null

    /** The label of the download button. */
    fun downloadLabel(availability: AiAvailability, report: ModelReport): String =
        if (availability is AiAvailability.Downloading || report.askedNotStarted) "Ask again" else "Download model"

    /** The detail under the pill for the states that need the report to be honest. */
    fun needsDownloadDetail(report: ModelReport): String =
        if (report.askedNotStarted) NOT_STARTED_DETAIL else "Google's service on the phone fetches the model. It is a large download; Wi-Fi is best."

    /**
     * Whether to send one tiny request now to wake the model, so the first real answer is faster. Only when the person switched
     * the AI on, never in Demo or lite mode, only when the model is usable, once per app start, and not more than once per [WARMUP_GAP_MS].
     */
    fun shouldWarmUp(on: Boolean, context: AiContext, availability: AiAvailability, report: ModelReport, lastTryMs: Long?, nowMs: Long): Boolean =
        on && !context.demo && !context.lite && (availability.canGenerate || context.modelWorked) &&
            !report.warmedUp && !report.warmingUp && (lastTryMs == null || nowMs - lastTryMs >= WARMUP_GAP_MS)

    private const val WAIT_HINT = "It usually waits for Wi-Fi, charging and free space (about 3 GB)."
    private const val MS_PER_S = 1000.0
}
