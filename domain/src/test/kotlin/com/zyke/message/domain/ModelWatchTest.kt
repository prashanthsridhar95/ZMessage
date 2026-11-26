package com.zyke.message.domain

import com.zyke.message.domain.ai.AiAvailability
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
}
