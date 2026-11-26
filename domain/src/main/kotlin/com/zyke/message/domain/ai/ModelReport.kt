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
    val lastProgressAtMs: Long? = null
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

    private const val WAIT_HINT = "It usually waits for Wi-Fi, charging and free space (about 3 GB)."
}
