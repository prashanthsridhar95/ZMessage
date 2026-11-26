package com.zyke.message.domain

import com.zyke.message.domain.ai.AiAvailability
import com.zyke.message.domain.ai.AiContext
import com.zyke.message.domain.ai.DownloadEvent
import com.zyke.message.domain.ai.ModelReport
import com.zyke.message.domain.ai.ModelWatch
import com.zyke.message.domain.ai.PhoneModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelWatchTest {
    private val idle = ModelReport()
    private val streaming = ModelReport(streamActive = true)

    @Test
    fun downloadingWithoutOurOwnStreamIsWaitingOnGoogleAndNeverStuck() {
        val d = AiAvailability.Downloading(null)
        assertTrue(ModelWatch.waitingOnGoogle(d, idle))
        assertTrue("the button re-attaches", ModelWatch.canDownload(d, idle))
    }

    @Test
    fun buttonIsOffOnlyWhileOurOwnStreamRuns() {
        val d = AiAvailability.Downloading(null)
        assertFalse(ModelWatch.waitingOnGoogle(d, streaming))
        assertFalse(ModelWatch.canDownload(d, streaming))
        assertTrue(ModelWatch.canDownload(AiAvailability.NeedsDownload, idle))
        assertTrue(ModelWatch.canDownload(AiAvailability.Failed("x"), idle))
        assertFalse(ModelWatch.canDownload(AiAvailability.Ready, idle))
        assertFalse(ModelWatch.canDownload(AiAvailability.Unsupported, idle))
    }

    @Test
    fun onlyDownloadingIsPolled() {
        assertTrue(ModelWatch.shouldPoll(AiAvailability.Downloading(null)))
        listOf(AiAvailability.Ready, AiAvailability.NeedsDownload, AiAvailability.Unsupported, AiAvailability.Busy)
            .forEach { assertFalse(ModelWatch.shouldPoll(it)) }
        assertEquals(5_000L, ModelWatch.POLL_MS)
    }

    @Test
    fun detailSaysPlainlyWhenNoBytesEverArrived() {
        assertTrue("No progress is reported by the phone yet" in ModelWatch.downloadingDetail(idle))
        assertTrue("about 3 GB" in ModelWatch.downloadingDetail(streaming))
        val seen = ModelWatch.downloadingDetail(ModelReport(megabytes = 120, lastProgressAtMs = 5L))
        assertTrue("120 MB downloaded" in seen)
        assertFalse("No progress" in seen)
    }

    @Test
    fun anAnswerProvesReadinessButBusyIsKept() {
        assertEquals(AiAvailability.Ready, ModelWatch.afterSuccessfulAnswer(AiAvailability.Downloading(null)))
        assertEquals(AiAvailability.Ready, ModelWatch.afterSuccessfulAnswer(AiAvailability.NeedsDownload))
        assertEquals(AiAvailability.Busy, ModelWatch.afterSuccessfulAnswer(AiAvailability.Busy))
    }

    @Test
    fun linesCarryOnlyWords() {
        val r = ModelReport(phoneStatus = PhoneModelStatus.DOWNLOADING, lastEvent = DownloadEvent.PROGRESS)
        assertEquals("phone says: downloading · download event: progress", r.line())
        assertEquals("Google's AI service reports: downloading", ModelWatch.serviceLine(r))
        assertEquals("phone says: not asked yet · download event: none", idle.line())
    }

    private val now = 10_000_000_000L
    private val worked = ModelReport(phoneStatus = PhoneModelStatus.NOT_DOWNLOADED, workedAtMs = now - 60_000, workedLatencyMs = 7_000)

    @Test
    fun notDownloadedWordButARecentAnswerIsReady() {
        assertEquals(AiAvailability.Ready, ModelWatch.effective(AiAvailability.NeedsDownload, worked, now))
        assertEquals(AiAvailability.Ready, ModelWatch.effective(AiAvailability.Unsupported, worked, now))
        assertEquals(AiAvailability.Ready, ModelWatch.effective(AiAvailability.Failed("x"), worked, now))
        assertEquals(AiAvailability.NeedsDownload, ModelWatch.effective(AiAvailability.NeedsDownload, idle, now))
        assertEquals(AiAvailability.Busy, ModelWatch.effective(AiAvailability.Busy, worked, now))
        val real = AiAvailability.Downloading(null)
        assertEquals("a download the phone really reports keeps its state", real, ModelWatch.effective(real, worked, now))
    }

    @Test
    fun anOldAnswerIsNotEvidence() {
        val old = worked.copy(workedAtMs = now - ModelWatch.RECENT_MS - 1)
        assertFalse(ModelWatch.worked(old, now))
        assertEquals(AiAvailability.NeedsDownload, ModelWatch.effective(AiAvailability.NeedsDownload, old, now))
        assertTrue(ModelWatch.worked(worked, now))
        assertFalse(ModelWatch.worked(idle, now))
    }

    @Test
    fun theLinesSayWhatHappened() {
        assertEquals("Working on this phone · last answer 7.0 s · 3:42", ModelWatch.workingLine(worked, "3:42").replace(',', '.'))
        assertEquals(
            "Android reports the model as not downloaded, but it answered at 3:42.",
            ModelWatch.disagreement(worked, "3:42", now)
        )
        assertEquals(null, ModelWatch.disagreement(worked.copy(phoneStatus = PhoneModelStatus.READY), "3:42", now))
        assertEquals(null, ModelWatch.disagreement(idle.copy(phoneStatus = PhoneModelStatus.NOT_DOWNLOADED), "3:42", now))
    }

    @Test
    fun aStreamThatShowsNothingExpiresAfterThirtySeconds() {
        val start = 1_000L
        val quiet = ModelReport(streamActive = true, streamStartedAtMs = start)
        assertFalse(ModelWatch.streamlessExpired(quiet, start + 29_999))
        assertTrue(ModelWatch.streamlessExpired(quiet, start + 30_000))
        assertFalse("progress keeps it alive", ModelWatch.streamlessExpired(quiet.copy(lastEvent = DownloadEvent.PROGRESS), start + 60_000))
        assertFalse("not our stream", ModelWatch.streamlessExpired(quiet.copy(streamActive = false), start + 60_000))
        assertFalse("never started", ModelWatch.streamlessExpired(ModelReport(streamActive = true), start + 60_000))
    }

    @Test
    fun askedButNotStartedIsHonestAndOffersAskAgain() {
        val asked = ModelReport(phoneStatus = PhoneModelStatus.NOT_DOWNLOADED, askedNotStarted = true)
        assertEquals("Asked, but the phone has not started. Try 'Try it' to see if it works anyway.", ModelWatch.needsDownloadDetail(asked))
        assertEquals("Ask again", ModelWatch.downloadLabel(AiAvailability.NeedsDownload, asked))
        assertEquals("Download model", ModelWatch.downloadLabel(AiAvailability.NeedsDownload, idle))
        assertEquals("Ask again", ModelWatch.downloadLabel(AiAvailability.Downloading(null), idle))
        assertTrue(ModelWatch.canDownload(AiAvailability.NeedsDownload, asked))
        assertTrue("a real DOWNLOADING still waits on Google", ModelWatch.waitingOnGoogle(AiAvailability.Downloading(null), idle))
    }

    @Test
    fun warmUpIsOptionalRateLimitedAndNeverInDemoOrLite() {
        val ok = AiContext()
        val ready = AiAvailability.Ready
        fun due(on: Boolean = true, ctx: AiContext = ok, a: AiAvailability = ready, r: ModelReport = idle, last: Long? = null, at: Long = now) =
            ModelWatch.shouldWarmUp(on, ctx, a, r, last, at)
        assertTrue(due())
        assertFalse("master off", due(on = false))
        assertFalse("demo", due(ctx = AiContext(demo = true)))
        assertFalse("lite", due(ctx = AiContext(lite = true)))
        assertFalse("model not usable", due(a = AiAvailability.NeedsDownload))
        assertTrue("usable by evidence", due(a = AiAvailability.NeedsDownload, ctx = AiContext(modelWorked = true)))
        assertFalse("already warm this start", due(r = ModelReport(warmedUp = true)))
        assertFalse("a request is in flight", due(r = ModelReport(warmingUp = true)))
        assertFalse("tried a minute ago", due(last = now - 60_000))
        assertTrue("tried long ago", due(last = now - ModelWatch.WARMUP_GAP_MS))
    }
}
