package com.zyke.message.domain.diag

import com.zyke.message.domain.flight.FlightStatusParser
import com.zyke.message.domain.train.TrainFixtures
import com.zyke.message.domain.train.TrainStatusParser
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
        assertNull(TrainStatusParser.firstMissingField(TrainFixtures.running))
        assertNull(TrainStatusParser.firstMissingField("<html>"))
        assertEquals("route (or stations, stops)", TrainStatusParser.firstMissingField("""{"hello":1}"""))
        assertEquals("station_code", TrainStatusParser.firstMissingField("""{"route":[{"name":"x"}]}"""))
        assertEquals("sch_arr (or sch_dep)", TrainStatusParser.firstMissingField("""{"route":[{"station_code":"MS"}]}"""))
        assertEquals("top-level object", TrainStatusParser.firstMissingField("[1]"))
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
}
