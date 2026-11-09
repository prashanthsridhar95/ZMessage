package com.zyke.message.domain.diag

import com.zyke.message.domain.flight.FlightStatusParser
import com.zyke.message.domain.train.TrainFixtures
import com.zyke.message.domain.train.IndianRailApiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCheckTest {
    private fun reason(code: Int, body: String?, net: NetworkProblem = NetworkProblem.NONE, large: Boolean = false, missing: String? = null) =
        LiveCheck.classify(code, body, net, large, missing)

    @Test fun networkProblemsComeFirst() {
        assertEquals(LiveFailure.DNS, reason(0, null, NetworkProblem.DNS).reason)
        assertEquals(LiveFailure.TLS, reason(0, null, NetworkProblem.TLS).reason)
        assertEquals(LiveFailure.TIMEOUT, reason(0, null, NetworkProblem.TIMEOUT).reason)
        assertEquals(LiveFailure.NO_NETWORK, reason(0, null, NetworkProblem.NO_NETWORK).reason)
        assertEquals(LiveFailure.OTHER, reason(0, null, NetworkProblem.OTHER).reason)
    }

    @Test fun refusedAndOtherStatusesAreTold_apart() {
        for (c in listOf(401, 403, 429, 451)) {
            val d = reason(c, "denied")
            assertEquals(LiveFailure.BLOCKED, d.reason)
            assertEquals(c.toString(), d.detail)
        }
        for (c in listOf(301, 302, 404, 500, 503)) assertEquals(LiveFailure.HTTP_STATUS, reason(c, "x").reason)
        assertEquals("HTTP 403", LiveCheck.describe(reason(403, "")).substringAfter("(").substringBefore(")"))
    }

    @Test fun aSuccessStatusWithNothingUsableIsNotBlocked() {
        assertEquals(LiveFailure.EMPTY_BODY, reason(200, null).reason)
        assertEquals(LiveFailure.EMPTY_BODY, reason(200, "  \n").reason)
        assertEquals(LiveFailure.TOO_LARGE, reason(200, null, large = true).reason)
        assertEquals(LiveFailure.NOT_JSON, reason(200, "<html>Are you a robot?</html>").reason)
        assertEquals(LiveFailure.NOT_JSON, reason(200, "{\"a\":").reason)
        assertEquals(LiveFailure.SHAPE_MISMATCH, reason(200, "12").reason)
        assertTrue(LiveFailure.NOT_JSON.isReplyProblem && LiveFailure.EMPTY_BODY.isReplyProblem)
        assertFalse(LiveFailure.BLOCKED.isReplyProblem || LiveFailure.TIMEOUT.isReplyProblem)
    }

    @Test fun aShapeMismatchNamesTheFirstMissingField() {
        val d = reason(200, "{\"x\":1}", missing = "route (or stations, stops)")
        assertEquals(LiveFailure.SHAPE_MISMATCH, d.reason)
        assertTrue("route (or stations, stops)" in LiveCheck.describe(d))
        assertTrue(reason(200, "{\"x\":1}").ok)
    }

    @Test fun theParsersNameTheMissingField() {
        assertNull(IndianRailApiParser.firstMissingField(TrainFixtures.running))
        assertNull(IndianRailApiParser.firstMissingField("<html>"))
        assertEquals("ResponseCode", IndianRailApiParser.firstMissingField("""{"hello":1}"""))
        assertEquals("StationCode", IndianRailApiParser.firstMissingField("""{"ResponseCode":"200","TrainRoute":[{"StationName":"x"}]}"""))
        assertEquals("ScheduleArrival (or ScheduleDeparture)", IndianRailApiParser.firstMissingField("""{"ResponseCode":"200","TrainRoute":[{"StationCode":"MS"}]}"""))
        assertEquals("top-level object", IndianRailApiParser.firstMissingField("[1]"))
        assertEquals("result", FlightStatusParser.firstMissingField("""{"a":1}"""))
        assertEquals("result.response", FlightStatusParser.firstMissingField("""{"result":{}}"""))
        assertEquals("result.response.data", FlightStatusParser.firstMissingField("""{"result":{"response":{}}}"""))
        assertNull(FlightStatusParser.firstMissingField("""{"result":{"response":{"data":[]}}}"""))
        assertEquals("time.scheduled.departure", FlightStatusParser.firstMissingField("""{"result":{"response":{"data":[{"status":{}}]}}}"""))
    }

    @Test fun cardWordsAreShortAndCalm() {
        assertEquals("Live status is unavailable right now (service blocked the request)", LiveCheck.cardLine(LiveFailure.BLOCKED, false, "train status"))
        assertTrue(LiveCheck.cardLine(LiveFailure.BLOCKED, true, "train status").endsWith("showing last known status"))
        assertTrue(LiveCheck.cardLine(LiveFailure.NOT_JSON, false, "train status").startsWith("Couldn’t read the live reply"))
        assertEquals("Couldn’t refresh · showing last known status", LiveCheck.cardLine(LiveFailure.TIMEOUT, true, "train status"))
        assertEquals("Couldn’t get live flight status · showing your booking", LiveCheck.cardLine(null, false, "flight status"))
        for (r in LiveFailure.values()) assertFalse("\n" in LiveCheck.cardLine(r, true, "x") || "\n" in LiveCheck.describe(LiveDiagnosis(r, "1")))
    }

    @Test fun sharedTextIsCutAt64KbAndSaysSo() {
        val reply = LiveReply(200, "application/json", "{}", 2)
        val report = LiveCheckReport("Train 12633", reply, LiveDiagnosis(LiveFailure.NONE), "found")
        val small = LiveCheck.shareText(report, "{\"a\":1}")
        assertTrue(small.startsWith("Zyke Message live status sample") && small.endsWith("{\"a\":1}"))
        assertTrue("personal data" in small && "Train 12633" in small && "HTTP status: 200" in small)
        val big = LiveCheck.truncate("é".repeat(100_000))
        assertTrue(big.endsWith(LiveCheck.TRUNCATED_NOTE))
        assertTrue(big.toByteArray(Charsets.UTF_8).size <= LiveCheck.SHARE_MAX_BYTES + LiveCheck.TRUNCATED_NOTE.length)
        assertFalse('�' in big)
        assertEquals("abc", LiveCheck.truncate("abc"))
    }

    @Test fun theCardLinesListEveryFact() {
        val r = LiveReply(403, "text/html", null, 0, tookMs = 120)
        val lines = LiveCheckReport("Flight 6E2345", r, reasonOf(r), null).lines()
        assertEquals(7, lines.size)
        assertTrue(lines.any { it == "HTTP status: 403" } && lines.any { it == "Content type: text/html" } && lines.any { it == "Parser: not run" })
    }

    private fun reasonOf(r: LiveReply) = LiveCheck.classify(r)

    @Test fun aKeyedServiceTellsTheKeyFromTheLimit() {
        for (c in listOf(401, 403)) {
            val d = LiveCheck.classify(c, "denied", keyed = true)
            assertEquals(LiveFailure.KEY_REJECTED, d.reason)
            assertEquals("HTTP $c", d.detail)
        }
        assertEquals(LiveFailure.LIMIT_REACHED, LiveCheck.classify(429, "slow down", keyed = true).reason)
        assertEquals(LiveFailure.BLOCKED, LiveCheck.classify(451, "", keyed = true).reason)
        assertEquals(LiveFailure.HTTP_STATUS, LiveCheck.classify(404, "", keyed = true).reason)
        // Without a key (flights) nothing changes.
        assertEquals(LiveFailure.BLOCKED, LiveCheck.classify(403, "denied").reason)
        assertEquals(LiveFailure.BLOCKED, LiveCheck.classify(429, "").reason)
        assertTrue("key" in LiveCheck.describe(LiveDiagnosis(LiveFailure.KEY_REJECTED, "HTTP 403")) && "HTTP 403" in LiveCheck.describe(LiveDiagnosis(LiveFailure.KEY_REJECTED, "HTTP 403")))
        assertTrue("limit" in LiveCheck.describe(LiveDiagnosis(LiveFailure.LIMIT_REACHED)))
    }

    @Test fun theTrainCardLinesAreTheOnesTheCardPromises() {
        assertEquals("Train data key was rejected", LiveCheck.cardLine(LiveFailure.KEY_REJECTED, false, "train status"))
        assertEquals("Daily limit reached · showing last known status", LiveCheck.cardLine(LiveFailure.LIMIT_REACHED, true, "train status"))
        assertEquals("Live status unavailable right now", LiveCheck.cardLine(LiveFailure.PROVIDER, false, "train status"))
        assertEquals("No running status for this train on this day", LiveCheck.NO_STATUS_LINE)
        assertEquals("Couldn’t make a secure connection · showing your booking", LiveCheck.cardLine(LiveFailure.TLS, false, "train status"))
        for (r in listOf(LiveFailure.KEY_REJECTED, LiveFailure.LIMIT_REACHED, LiveFailure.PROVIDER)) assertFalse(r.isReplyProblem)
    }

    @Test fun theSharedTextNeverHoldsTheKey() {
        val reply = LiveReply(200, "application/json", "{}", 2)
        val request = com.zyke.message.domain.train.TrainApi.redactedRequest("12676", java.time.LocalDate.of(2026, 11, 9))
        val report = LiveCheckReport("Train 12676", reply, LiveDiagnosis(LiveFailure.NONE), "found", "ResponseCode 200", request)
        val text = LiveCheck.shareText(report, """{"echo":"https://indianrailapi.com/api/v2/livetrainstatus/apikey/SECRET123/trainnumber/12676/date/20261109/"}""")
        assertFalse("SECRET123" in text)
        assertTrue("https://indianrailapi.com/api/v2/livetrainstatus/apikey/<key>/trainnumber/12676/date/20261109/" in text)
        assertTrue("Provider: ResponseCode 200" in text)
    }
}
