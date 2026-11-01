package com.zyke.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The black box is always on, so it must stay free of anything a person typed or received: an exception's own message
 * (which can quote text) is never written, and it must be started before everything else.
 */
class BlackBoxPolicyTest {
    private val blackBox = File("src/main/java/com/zyke/message/data/diag/BlackBox.kt").readText()

    @Test
    fun exceptionMessagesAreNeverWritten() {
        assertFalse(Regex("""\.message\b|\.localizedMessage|stackTraceToString""").containsMatchIn(blackBox))
        assertTrue(".stackTrace.take(" in blackBox)
    }

    @Test
    fun writingNeverThrowsAndStaysSmall() {
        assertTrue("MAX_BYTES = 256L * 1024L" in blackBox)
        assertTrue("runCatching" in blackBox)
    }

    @Test
    fun theApplicationInstallsItFirst() {
        val app = File("src/main/java/com/zyke/message/ZykeMessagesApplication.kt").readText()
        val onCreate = app.substringAfter("override fun onCreate()")
        assertTrue(onCreate.indexOf("BlackBox.install(this)") in 0 until onCreate.indexOf("DevLog.init"))
        assertTrue(onCreate.indexOf("DevLog.init") < onCreate.indexOf("container"))
    }

    @Test
    fun theShareHelperDoesNotNeedTheApp() {
        val share = File("src/main/java/com/zyke/message/data/diag/LogShare.kt").readText()
        assertFalse("AppContainer" in share || "SharedPreferences" in share || "androidx.compose" in share)
    }
}
