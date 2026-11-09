package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer's "Live status check" (Settings, Flights and trains): it must stay a developer-only tool that adds no network code, no
 * new host and no stored or logged reply, and the fetchers must stay safe on every Android version the app supports.
 */
class LiveStatusCheckPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun text(path: String) = File(main, path).readText()
    private val tools get() = text("presentation/settings/LiveStatusChecks.kt")
    private val memo get() = text("data/diag/LiveReplyMemo.kt")

    @Test fun theToolsAreOnlyForDevelopersAndNeverInTheDemo() {
        val screen = text("presentation/settings/SettingsScreen.kt")
        assertTrue("if (developerOn) LiveStatusChecks()" in screen)
        assertTrue("DevLog.developerMode.collectAsStateWithLifecycle()" in screen.substringBefore("if (developerOn) LiveStatusChecks()").takeLast(300))
        assertTrue("if (container.demo) return" in tools)
        for (service in listOf("data/trains/TrainService.kt", "data/flights/FlightService.kt")) {
            val diagnose = text(service).substringAfter("suspend fun diagnose(").substringBefore("withContext")
            assertTrue("$service must not ask in the demo", "if (demo) return null" in diagnose)
        }
    }

    @Test fun theToolsHaveNoNetworkCodeOfTheirOwn_andAskOnlyThroughTheTwoServices() {
        assertFalse(Regex("HttpURLConnection|openConnection|java\\.net|https?://|Fetcher").containsMatchIn(tools + memo))
        assertTrue("container.trains.diagnose(" in tools && "container.flights.diagnose(" in tools)
        assertTrue("fetcher.diagnose(" in text("data/trains/TrainService.kt") && "fetcher.diagnose(" in text("data/flights/FlightService.kt"))
        // The samples are public numbers, not the person's trips.
        assertTrue("SAMPLE_TRAIN = \"12676\"" in tools && "SAMPLE_FLIGHT = \"6E 2345\"" in tools)
    }

    @Test fun onlyTheLatestRawReplyIsKept_inMemory_neverStoredOrLogged() {
        assertFalse(Regex("DevLog|BlackBox|Log\\.|println|File\\(|SharedPreferences|getSharedPreferences|writeText|openFileOutput|Context").containsMatchIn(memo))
        assertEquals(1, Regex("private var held").findAll(memo).count())
        assertTrue("@Volatile" in memo)
        // Nothing in the services or the tools logs or stores a reply or its body.
        for (f in listOf(tools, text("data/trains/TrainService.kt"), text("data/flights/FlightService.kt"))) {
            val logged = f.lines().filter { "DevLog." in it }
            assertFalse(logged.any { Regex("body|report|raw", RegexOption.IGNORE_CASE).containsMatchIn(it.substringAfter("{", "")) })
        }
    }

    @Test fun theRawReplyIsSharedThroughTheLogShareHelper_cutAt64Kb() {
        assertTrue("LogShare.shareText(" in tools)
        assertFalse("startActivity" in tools || "Intent" in tools)
        assertTrue("fun shareText(context: Context" in text("data/diag/LogShare.kt"))
        assertTrue("LiveCheck.shareText(" in memo)
        val domain = File("../domain/src/main/kotlin/com/zyke/message/domain/diag/LiveCheck.kt").readText()
        assertTrue("const val SHARE_MAX_BYTES = 64 * 1024" in domain)
        assertTrue("Public status reply only; no messages, PNR or personal data." in domain)
    }

    @Test fun theFetchersAreTolerantAndSafeOnOlderAndroid() {
        for (name in listOf("TrainFetcher", "FlightFetcher")) {
            val f = text("data/markets/$name.kt")
            assertTrue("Accept-Language" in f && "\"Accept\"" in f)
            assertTrue("instanceFollowRedirects = false" in f)
            assertTrue("NetworkProblem.TIMEOUT) once(" in f)
            assertTrue("fun diagnose(" in f)
            // InputStream.readNBytes(int) needs Android 13; the app's minimum is Android 8.
            assertFalse("$name must not call readNBytes", "readNBytes(" in f.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n"))
        }
    }

    @Test fun theCardsSayBlockedApartFromUnreadable() {
        for (ui in listOf("presentation/trains/TrainUi.kt", "presentation/flights/FlightUi.kt")) {
            assertTrue("LiveCheck.cardLine(reason" in text(ui))
        }
        assertTrue("service blocked the request" in File("../domain/src/main/kotlin/com/zyke/message/domain/diag/LiveCheck.kt").readText())
    }
}
