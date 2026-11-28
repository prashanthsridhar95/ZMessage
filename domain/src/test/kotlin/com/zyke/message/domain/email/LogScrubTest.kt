package com.zyke.message.domain.email

import com.zyke.message.domain.diag.LogScrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogScrubTest {
    @Test
    fun anAddressAndAMailLoginAreTakenOutOfALogLine() {
        assertEquals("sign-in as <email> failed", LogScrub.scrub("sign-in as me.name+x@gmail.com failed"))
        val line = LogScrub.scrub("sent: Z001 LOGIN \"me@gmail.com\" \"ab\\\"cd\\\\efgh1234\" and more")
        assertFalse("gmail" in line || "efgh" in line)
        assertEquals("LOGIN <hidden>", Regex("LOGIN <hidden>").find(line)?.value)
    }

    @Test
    fun otherTextIsLeftAlone() {
        val text = "MainActivity started (SMS permission=true) 12 alerts, 3 not understood"
        assertEquals(text, LogScrub.scrub(text))
    }
}
