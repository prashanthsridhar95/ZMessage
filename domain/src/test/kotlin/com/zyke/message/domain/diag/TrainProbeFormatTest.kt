package com.zyke.message.domain.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainProbeFormatTest {
    private fun result(
        status: Int = 200, net: NetworkProblem = NetworkProblem.NONE, type: String? = "application/json", body: String? = "{}",
        bytes: Int = 2, cut: Boolean = false, location: String? = null, index: Int = 1
    ) = ProbeResult(index, "Label", "https://www.example.com/x?y=1", "www.example.com", status, net, type, bytes, cut, body, location, 478L)

    @Test fun hostIsReadFromTheAddress() {
        assertEquals("www.railyatri.in", ProbeCandidate("a", "https://www.railyatri.in/live-train-status/12676?start_date=2026-11-09").host)
        assertEquals("api.confirmtkt.com", ProbeCandidate("a", "https://api.confirmtkt.com?x=1").host)
    }

    @Test fun singleLineFlattensControlCharactersAndWhiteSpace() {
        assertEquals("a b c d", TrainProbeFormat.singleLine("  a\n\tb\r\n  c\u0000\u0007d  ", 50))
        assertEquals("", TrainProbeFormat.singleLine(null, 10))
        assertEquals("", TrainProbeFormat.singleLine(" \n\t ", 10))
    }

    @Test fun singleLineCutsWithAnEllipsisAndKeepsSurrogatePairsWhole() {
        val long = "x".repeat(300)
        val cut = TrainProbeFormat.singleLine(long, 160)
        assertEquals(161, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("abc", TrainProbeFormat.singleLine("abc", 3))
        val emoji = "ab😀cd" // the pair sits across the cut
        val out = TrainProbeFormat.singleLine(emoji, 3)
        assertEquals("ab…", out)
        assertFalse(out.any { it.isHighSurrogate() })
    }

    @Test fun outcomeNamesTheFailureKind() {
        assertEquals("DNS failure", TrainProbeFormat.outcome(result(0, NetworkProblem.DNS)))
        assertEquals("TLS failure", TrainProbeFormat.outcome(result(0, NetworkProblem.TLS)))
        assertEquals("timeout", TrainProbeFormat.outcome(result(0, NetworkProblem.TIMEOUT)))
        assertEquals("no connection", TrainProbeFormat.outcome(result(0, NetworkProblem.NO_NETWORK)))
        assertEquals("other failure", TrainProbeFormat.outcome(result(0, NetworkProblem.OTHER)))
        assertEquals("HTTP 200", TrainProbeFormat.outcome(result(200)))
        assertEquals("HTTP 404", TrainProbeFormat.outcome(result(404)))
        for (code in listOf(401, 403, 429, 451)) assertEquals("blocked (HTTP $code)", TrainProbeFormat.outcome(result(code)))
    }

    @Test fun aRedirectShowsItsLocation() {
        assertEquals("HTTP 301 to https://www.ixigo.com/a", TrainProbeFormat.outcome(result(301, location = "https://www.ixigo.com/a")))
        assertEquals("HTTP 302 (no Location)", TrainProbeFormat.outcome(result(302)))
        assertEquals("HTTP 302 to https://a/b c", TrainProbeFormat.outcome(result(302, location = "https://a/b\nc")))
    }

    @Test fun resultLinesAreThreeSingleLines() {
        val lines = TrainProbeFormat.resultLines(result(body = "{\n  \"a\": 1\n}", index = 4))
        assertEquals(3, lines.size)
        assertEquals("4. Label", lines[0])
        assertEquals("www.example.com · HTTP 200 · application/json · 2 B · 478 ms", lines[1])
        assertEquals("{ \"a\": 1 }", lines[2])
        assertTrue(lines.none { '\n' in it })
    }

    @Test fun aFailureHasNoTypeOrSizeAndSaysNoBody() {
        val lines = TrainProbeFormat.resultLines(result(0, NetworkProblem.DNS, null, null, 0))
        assertEquals("www.example.com · DNS failure · 478 ms", lines[1])
        assertEquals("(no body)", lines[2])
    }

    @Test fun previewIsAtMost160CharactersPlusEllipsis() {
        val p = TrainProbeFormat.preview(result(body = "y".repeat(1000)))
        assertEquals(TrainProbeFormat.PREVIEW_CHARS + 1, p.length)
    }

    @Test fun missingContentTypeAndCutSizeAreSaid() {
        assertTrue("no content type" in TrainProbeFormat.facts(result(type = null)))
        assertTrue("16384 B+ (cut)" in TrainProbeFormat.facts(result(bytes = 16384, cut = true)))
    }

    @Test fun bodiesAreCappedAt16KbInBytes() {
        val small = "é".repeat(100)
        assertEquals(small, TrainProbeFormat.capBody(small))
        val big = "é".repeat(20_000) // 40,000 bytes
        val capped = TrainProbeFormat.capBody(big)
        assertTrue(capped.endsWith(TrainProbeFormat.CUT_NOTE))
        assertTrue(capped.removeSuffix(TrainProbeFormat.CUT_NOTE).toByteArray(Charsets.UTF_8).size <= TrainProbeFormat.BODY_READ_MAX)
    }

    @Test fun shareTextLabelsEveryCandidateAndCapsEveryBody() {
        val a = result(index = 1, body = "A".repeat(40_000), bytes = 16384, cut = true)
        val b = result(0, NetworkProblem.DNS, null, null, 0, index = 2)
        val c = result(302, location = "https://www.ixigo.com/z", body = "", index = 3)
        val text = TrainProbeFormat.shareText(listOf(a, b, c), "12676")
        assertTrue("===== 1. Label (www.example.com) =====" in text)
        assertTrue("===== 2. Label (www.example.com) =====" in text)
        assertTrue("===== 3. Label (www.example.com) =====" in text)
        assertTrue("sample train number 12676" in text)
        assertTrue("Location: https://www.ixigo.com/z" in text)
        assertTrue("Result: DNS failure · 478 ms" in text)
        assertTrue(text.length < 3 * TrainProbeFormat.BODY_READ_MAX)
        assertEquals(2, Regex("\\(no body\\)").findAll(text).count())
    }
}
