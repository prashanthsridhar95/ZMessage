package com.zyke.message.domain

import com.zyke.message.domain.balance.BalanceRefresh
import com.zyke.message.domain.balance.BalanceRequest
import com.zyke.message.domain.balance.RefreshCandidate
import com.zyke.message.domain.balance.RefreshEntry
import com.zyke.message.domain.balance.RefreshFacts
import com.zyke.message.domain.balance.RefreshOutcome
import com.zyke.message.domain.balance.RefreshRun
import com.zyke.message.domain.balance.RefreshSkip
import com.zyke.message.domain.balance.RequestStatus
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class BalanceRefreshTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(h: Int, m: Int, day: Int = 10) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val digest = at(8, 0)
    private val now = at(7, 55)
    private val ok = RefreshFacts(canSend = true, hasSms = true, airplane = false, readingPaused = false)
    private fun c(key: String, request: String? = "n|Balance", confirmed: Boolean = true) = RefreshCandidate(key, "Bank $key", request, confirmed)

    @Test fun theAlarmIsFiveMinutesBeforeTheDigest_orTomorrowsWhenThatHasPassed() {
        assertEquals(at(7, 55), BalanceRefresh.alarmAt(at(6, 0), digest, at(8, 0, 11)))
        assertEquals(at(7, 55, 11), BalanceRefresh.alarmAt(at(7, 57), digest, at(8, 0, 11)))
        assertEquals(at(7, 55, 11), BalanceRefresh.alarmAt(at(7, 55), digest, at(8, 0, 11)))
    }

    @Test fun nothingIsAskedWhenThePhoneCannotText() {
        assertNull(BalanceRefresh.blockedForAll(ok))
        assertEquals(RefreshSkip.READING_PAUSED, BalanceRefresh.blockedForAll(ok.copy(readingPaused = true)))
        assertEquals(RefreshSkip.NO_PERMISSION, BalanceRefresh.blockedForAll(ok.copy(canSend = false)))
        assertEquals(RefreshSkip.NO_SMS, BalanceRefresh.blockedForAll(ok.copy(hasSms = false)))
        assertEquals(RefreshSkip.AIRPLANE, BalanceRefresh.blockedForAll(ok.copy(airplane = true)))
        val entries = BalanceRefresh.plan(listOf(c("a")), emptyMap(), now, digest, zone, RefreshSkip.AIRPLANE)
        assertEquals(listOf(RefreshSkip.AIRPLANE), entries.map { it.skip })
        assertNull(entries.single().askedAt)
    }

    @Test fun onlyAConfirmedBankWithANumberIsAsked() {
        assertEquals(RefreshSkip.NOT_SET_UP, BalanceRefresh.skipFor(c("a", request = null), null, now, digest, zone))
        assertEquals(RefreshSkip.NOT_CONFIRMED, BalanceRefresh.skipFor(c("a", confirmed = false), null, now, digest, zone))
        assertNull(BalanceRefresh.skipFor(c("a"), null, now, digest, zone))
    }

    @Test fun spacingIsOncePerDay_andNeverWithinSixHours() {
        fun last(at: Long, status: RequestStatus = RequestStatus.SENT) = BalanceRequest("a", at, status)
        // Yesterday evening, 10 hours ago: allowed.
        assertNull(BalanceRefresh.skipFor(c("a"), last(at(21, 55, 9)), now, digest, zone))
        // Yesterday 23:00 is 8 h 55 min ago: allowed; at 4:00 it was 5 hours ago and across midnight: too soon.
        assertNull(BalanceRefresh.skipFor(c("a"), last(at(23, 0, 9)), now, digest, zone))
        assertEquals(RefreshSkip.TOO_SOON, BalanceRefresh.skipFor(c("a"), last(at(23, 0, 9)), at(4, 0), at(4, 10), zone))
        // Today 00:30 (7 hours ago) is a second check on one day.
        assertEquals(RefreshSkip.ALREADY_TODAY, BalanceRefresh.skipFor(c("a"), last(at(0, 30)), now, digest, zone))
        // A manual check at 7:00 counts as well.
        assertEquals(RefreshSkip.TOO_SOON, BalanceRefresh.skipFor(c("a"), last(at(7, 0)), now, digest, zone))
        // A request the phone failed to send does not count.
        assertNull(BalanceRefresh.skipFor(c("a"), last(at(7, 0), RequestStatus.FAILED), now, digest, zone))
    }

    @Test fun aRefreshThatWakesLateSendsNothing() {
        assertEquals(RefreshSkip.TOO_LATE, BalanceRefresh.skipFor(c("a"), null, at(7, 59), digest, zone))
        assertEquals(RefreshSkip.TOO_LATE, BalanceRefresh.skipFor(c("a"), null, at(8, 5), digest, zone))
        assertNull(BalanceRefresh.skipFor(c("a"), null, at(7, 58), digest, zone))
    }

    @Test fun twoAccountsWithTheSameTextAreAskedOnce_andNoMoreThanTheCap() {
        val same = BalanceRefresh.plan(listOf(c("a"), c("b"), c("c", request = "other|BAL")), emptyMap(), now, digest, zone, null)
        assertEquals(listOf(null, RefreshSkip.COVERED, null), same.map { it.skip })
        val many = BalanceRefresh.plan((1..8).map { c("k$it", request = "n|$it") }, emptyMap(), now, digest, zone, null)
        assertEquals(BalanceRefresh.MAX_ASKS, many.count { it.askedAt != null })
        assertEquals(RefreshSkip.LIMIT, many.last().skip)
    }

    @Test fun outcomesFollowTheReplyAndTheWindow() {
        val e = RefreshEntry("a", "Bank a", now)
        assertEquals(RefreshOutcome.WAITING, BalanceRefresh.outcome(e, 0L, now + 60_000))
        assertEquals(RefreshOutcome.REPLIED, BalanceRefresh.outcome(e, now + 60_000, now + 90_000))
        assertEquals(RefreshOutcome.REPLIED, BalanceRefresh.outcome(e, now, now + 90_000))
        assertEquals(RefreshOutcome.NO_REPLY, BalanceRefresh.outcome(e, now - 1, now + BalanceRefresh.WINDOW_MS))
        assertEquals(RefreshOutcome.FAILED, BalanceRefresh.outcome(e.copy(failed = true), 0L, now))
        assertEquals(RefreshOutcome.SKIPPED, BalanceRefresh.outcome(RefreshEntry("a", "x", null, RefreshSkip.AIRPLANE), 0L, now))
    }

    @Test fun theSummaryCountsOnlyAccountsThatWereAsked() {
        val o = RefreshOutcome.values()
        assertNull(BalanceRefresh.summary(emptyList()))
        assertNull(BalanceRefresh.summary(listOf(RefreshOutcome.SKIPPED)))
        assertEquals("Balances refreshed for 1 of 1 account", BalanceRefresh.summary(listOf(RefreshOutcome.REPLIED, RefreshOutcome.SKIPPED)))
        assertEquals("Balances refreshed for 2 of 4 accounts", BalanceRefresh.summary(listOf(RefreshOutcome.REPLIED, RefreshOutcome.REPLIED, RefreshOutcome.NO_REPLY, RefreshOutcome.FAILED, RefreshOutcome.SKIPPED)))
        assertEquals(5, o.size)
    }

    @Test fun theDigestWaitsOnlyForARepliesStillDue_andNeverLongerThanTheCap() {
        val run = RefreshRun(digest, now, listOf(RefreshEntry("a", "A", now), RefreshEntry("b", "B", null, RefreshSkip.TOO_SOON)))
        assertEquals(0L, BalanceRefresh.digestWaitMs(null, emptyMap(), now))
        // Asked 10 s ago, 2 min 50 s left in the window: capped.
        assertEquals(BalanceRefresh.DIGEST_WAIT_CAP_MS, BalanceRefresh.digestWaitMs(run, emptyMap(), now + 10_000))
        // 20 s left of the window: wait for that.
        assertEquals(20_000L, BalanceRefresh.digestWaitMs(run, emptyMap(), now + BalanceRefresh.WINDOW_MS - 20_000))
        // The reply came, or the window is over: no wait.
        assertEquals(0L, BalanceRefresh.digestWaitMs(run, mapOf("a" to now + 5_000), now + 10_000))
        assertEquals(0L, BalanceRefresh.digestWaitMs(run, emptyMap(), now + BalanceRefresh.WINDOW_MS))
    }

    @Test fun aRunBelongsToTheDigestItWasMadeFor() {
        val run = RefreshRun(digest, now, emptyList())
        assertEquals(true, BalanceRefresh.isCurrent(run, digest))
        assertEquals(true, BalanceRefresh.isCurrent(run, digest + 60 * 60_000L))
        assertEquals(false, BalanceRefresh.isCurrent(run, digest + 24 * 60 * 60_000L))
        assertEquals(false, BalanceRefresh.isCurrent(null, digest))
    }

    @Test fun theLogLinesReadLikeTheOnesInTheDesign() {
        val l = Locale.ENGLISH
        val asked = RefreshEntry("a", "HDFC Bank •••• 5765", at(8, 55))
        assertEquals("Asked 8:55 AM · reply 8:56 AM", BalanceRefresh.logLine(asked, RefreshOutcome.REPLIED, at(8, 56), zone, l))
        assertEquals("Asked 8:55 AM · no reply", BalanceRefresh.logLine(asked, RefreshOutcome.NO_REPLY, 0L, zone, l))
        assertEquals("Asked 8:55 AM · waiting for the reply", BalanceRefresh.logLine(asked, RefreshOutcome.WAITING, 0L, zone, l))
        assertEquals("Asked 8:55 AM · could not send", BalanceRefresh.logLine(asked.copy(failed = true), RefreshOutcome.FAILED, 0L, zone, l))
        assertEquals("Skipped · already checked today", BalanceRefresh.logLine(RefreshEntry("a", "x", null, RefreshSkip.ALREADY_TODAY), RefreshOutcome.SKIPPED, 0L, zone, l))
    }
}
