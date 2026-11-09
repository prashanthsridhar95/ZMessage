package com.zyke.message.domain.train

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainApiTest {
    @Test fun aKeyIsOneWordOfAtMostAHundredCharacters() {
        assertNull(TrainApi.keyProblem("a1b2c3d4e5f6"))
        assertNull(TrainApi.keyProblem("  a1b2c3  "))
        assertNull(TrainApi.keyProblem("a".repeat(100)))
        assertNotNull(TrainApi.keyProblem("a".repeat(101)))
        assertNotNull(TrainApi.keyProblem(""))
        assertNotNull(TrainApi.keyProblem("   "))
        assertEquals("A key has no spaces", TrainApi.keyProblem("abc def"))
        assertEquals("A key has no spaces", TrainApi.keyProblem("abc\tdef"))
    }

    @Test fun charactersThatWouldChangeTheAddressAreNotAKey() {
        for (bad in listOf("ab/cd", "ab?cd", "ab#cd", "ab%2Fcd", "ab\\cd", "ab\"cd", "abé", "ab<cd>")) assertNotNull(bad, TrainApi.keyProblem(bad))
        for (good in listOf("AbC-123_x.y~z", "k3y+/".dropLast(1))) assertNull(good, TrainApi.keyProblem(good))
        assertEquals("abc", TrainApi.cleanKey("  abc \n"))
        assertTrue(TrainApi.isValidKey("abc"))
    }

    @Test fun theDateIsYyyymmdd() {
        assertEquals("20261109", TrainApi.dateParam(LocalDate.of(2026, 11, 9)))
        assertEquals("20180915", TrainApi.dateParam(LocalDate.of(2018, 9, 15)))
    }

    @Test fun theRequestShownToADeveloperHasNoKey() {
        assertEquals(
            "https://indianrailapi.com/api/v2/livetrainstatus/apikey/<key>/trainnumber/12676/date/20261109/",
            TrainApi.redactedRequest("12676", LocalDate.of(2026, 11, 9))
        )
        assertTrue(TrainApi.redactedRequest("12676 x", LocalDate.of(2026, 11, 9)).contains("/trainnumber/12676/"))
    }

    @Test fun theKeyIsRemovedWhereverItAppears() {
        val key = "S3cretK3y"
        val url = "https://indianrailapi.com/api/v2/livetrainstatus/apikey/$key/trainnumber/12676/date/20261109/"
        assertFalse(key in TrainApi.redact(url, key))
        assertFalse(key in TrainApi.redact(url))
        assertEquals("see https://indianrailapi.com/api/v2/livetrainstatus/apikey/<key>/trainnumber/12676/date/20261109/ now", TrainApi.redact("see $url now"))
        assertEquals("error for $key", TrainApi.redact("error for $key").replace("<key>", key))
        assertEquals("error for <key>", TrainApi.redact("error for $key", key))
        assertEquals("x?apikey=<key>&y=1", TrainApi.redact("x?apikey=$key&y=1"))
        assertEquals("x?api_key=<key>", TrainApi.redact("x?api_key=$key"))
        // Redacting twice changes nothing.
        assertEquals(TrainApi.redact(url), TrainApi.redact(TrainApi.redact(url)))
        assertEquals("nothing here", TrainApi.redact("nothing here", key))
    }
}
