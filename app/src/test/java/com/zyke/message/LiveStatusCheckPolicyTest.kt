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

    @Test fun theTrainProbeIsGone() {
        assertFalse("TrainProbe" in tools || "Probe train sources" in tools || "probe" in tools.lowercase())
        assertFalse(File(main, "data/markets/TrainProbe.kt").exists() || File(main, "data/diag/TrainProbeMemo.kt").exists())
        assertFalse(File("../domain/src/main/kotlin/com/zyke/message/domain/diag/TrainProbe.kt").exists())
        // The one equal-width pair of buttons the probe had is gone with it; the remaining buttons are full width.
        assertFalse("ResponsiveRow(" in tools)
    }

    @Test fun theTrainTestUsesTheKeyAndASampleForTodayButNeverShowsOrSharesTheKey() {
        val service = text("data/trains/TrainService.kt")
        val diagnose = service.substringAfter("suspend fun diagnose(").substringBefore("/** One run's reply")
        // The key comes from the settings, never from the screen; the sample is today's run of train 12676.
        assertTrue("settings.key()" in diagnose && "LocalDate.now(Stations.ZONE)" in diagnose)
        assertTrue("SAMPLE_TRAIN = \"12676\"" in tools && "container.trains.diagnose(SAMPLE_TRAIN)" in tools)
        // The request shown is the address with a placeholder; the reply and the provider's words are redacted before they are kept.
        assertTrue("TrainApi.redactedRequest(" in diagnose)
        assertTrue("LiveReplyMemo.put(report, TrainApi.redact(body.orEmpty(), key))" in diagnose)
        assertTrue("TrainApi.redact(it, key)" in diagnose)
        // The shared text is redacted once more, whatever put it together.
        val liveCheck = File("../domain/src/main/kotlin/com/zyke/message/domain/diag/LiveCheck.kt").readText()
        assertTrue("return TrainApi.redact(head + truncate(body))" in liveCheck)
        assertFalse(Regex("settings\\.key\\(\\)|getKey|\\.maskedKey|api_key").containsMatchIn(tools + memo))
        // Nothing on this screen reads the key at all.
        assertFalse("key()" in tools)
    }

    @Test fun theTrainFetcherNeverLogsTheKeyNorPutsItInAnException() {
        val fetcher = text("data/markets/TrainFetcher.kt")
        val code = fetcher.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }
        val logged = code.filter { "DevLog." in it || "Log." in it || "println(" in it }
        assertTrue(logged.isNotEmpty())
        // Status codes and exception class names only: no address, no key, no message text.
        for (line in logged) {
            val body = line.substringAfter("{", "")
            assertFalse("log line may hold the address or key: $line", Regex("address|key|safeKey|\\.message|localizedMessage|stackTrace|body|toString").containsMatchIn(body))
        }
        assertFalse(Regex("\\b(e|it|error|t)\\.message\\b|localizedMessage|stackTraceToString|printStackTrace").containsMatchIn(code.joinToString("\n")))
        assertTrue("e.javaClass.simpleName" in fetcher)
        // HTTPS only: one address, and it is https; there is no second attempt on another scheme.
        assertEquals(1, Regex("\"https://").findAll(fetcher).count())
        assertFalse(Regex("(?<![a-z])http://").containsMatchIn(code.joinToString("\n")))
        assertTrue("instanceFollowRedirects = false" in fetcher)
        // The host is pinned in the address itself and is the one the legal text names.
        assertTrue("\"https://indianrailapi.com/api/v2/livetrainstatus/apikey/" in fetcher)
        assertEquals("indianrailapi.com", com.zyke.message.domain.legal.LegalText.HOST_TRAINS)
        // A key that cannot be one is not sent, and what is sent is percent-encoded.
        assertTrue("TrainApi.isValidKey(key)" in fetcher && "URLEncoder.encode(" in fetcher)
    }

    @Test fun aSecureConnectionFailureHasItsOwnCardLineAndNeverFallsBack() {
        assertTrue("Couldn’t make a secure connection" in File("../domain/src/main/kotlin/com/zyke/message/domain/diag/LiveCheck.kt").readText())
        assertTrue("is SSLException -> NetworkProblem.TLS" in text("data/markets/TrainFetcher.kt"))
    }

    @Test fun theTrainCardNamesTheKeyAndTheLimitAndTheMissingStatus() {
        val domain = File("../domain/src/main/kotlin/com/zyke/message/domain/diag/LiveCheck.kt").readText()
        for (line in listOf("Train data key was rejected", "Daily limit reached", "Live status unavailable right now", "No running status for this train on this day")) assertTrue(line, line in domain)
        assertTrue("LiveCheck.NO_STATUS_LINE" in text("presentation/trains/TrainUi.kt"))
        assertTrue("LiveCheck.classify(reply, keyed = true)" in text("data/trains/TrainService.kt"))
    }
}
