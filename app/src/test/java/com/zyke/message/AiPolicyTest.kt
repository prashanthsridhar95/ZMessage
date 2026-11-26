package com.zyke.message

import com.zyke.message.domain.ai.AiSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device AI (Gemini Nano through ML Kit GenAI) is optional and must stay contained: one file touches the library, nothing
 * runs unless the person switched it on, nothing runs in Demo mode or from the SMS receiver, nothing is logged, nothing leaves the
 * phone from our code, and its files are in no backup or export. These tests read the sources, so a change that breaks one fails the build.
 */
class AiPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private fun repoRoot(): File = appRoot().canonicalFile.let { if (it.name == "app") it.parentFile else it }
    private val main get() = File(appRoot(), "src/main/java/com/zyke/message")
    private fun text(rel: String) = File(main, rel).readText()
    private fun kotlin(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(file: File) = file.readLines().map { it.substringBefore("//") }.filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") }.joinToString("\n")

    private val aiFiles get() = kotlin(File(main, "data/ai")) + kotlin(File(main, "presentation/ai")) +
        kotlin(File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai"))

    @Test
    fun theLibraryIsImportedInOneFileOnly() {
        val sources = kotlin(main) + kotlin(File(repoRoot(), "domain/src/main/kotlin")) + kotlin(File(repoRoot(), "wear/src")).filter { it.exists() }
        val users = sources.filter { "com.google.mlkit" in code(it) }.map { it.name }
        assertEquals(listOf("GeminiNanoEngine.kt"), users)
        assertTrue(File(main, "data/ai/GeminiNanoEngine.kt").readText().contains("class GeminiNanoEngine(private val evidence: ModelEvidenceStore) : OnDeviceAi"))
        val domain = kotlin(File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai")).joinToString("\n") { code(it) }
        assertFalse("the domain port knows no Android and no Google library", Regex("""import (android|com\.google|androidx)\.""").containsMatchIn(domain))
    }

    @Test
    fun theDependencyIsDeclaredOnceWithTheAppsMinSdk() {
        val toml = File(repoRoot(), "gradle/libs.versions.toml").readText()
        assertTrue("""mlkit-genai-prompt = { group = "com.google.mlkit", name = "genai-prompt"""" in toml)
        val gradle = File(appRoot(), "build.gradle.kts").readText()
        assertTrue("implementation(libs.mlkit.genai.prompt)" in gradle)
        assertTrue("the library needs API 26", (Regex("""minSdk = (\d+)""").find(gradle)?.groupValues?.get(1)?.toInt() ?: 0) >= 26)
        assertFalse("no cloud fallback library", listOf("firebase", "generativeai", "vertexai").any { it in toml.lowercase() + gradle.lowercase() })
    }

    @Test
    fun nothingRunsFromTheSmsReceiverOrTheBackgroundComponents() {
        // The one exception is the morning digest reading a line prepared earlier through a read-only object (AiExtrasPolicyTest pins that it never asks).
        val offenders = kotlin(File(main, "notifications")).plus(kotlin(File(main, "data/sms"))).filter {
            Regex("""\bAiHub\b|domain\.ai\b|data\.ai\b|\.container\.ai\b|presentation\.ai\b""").containsMatchIn(code(it).replace("com.zyke.message.data.ai.DigestNarrativeStore", ""))
        }.map { it.name }
        assertEquals("no AI in receivers, services, widgets or readers", emptyList<String>(), offenders)
    }

    @Test
    fun everythingIsOffUntilTheMasterSwitchAndTheExplanation() {
        val s = AiSettings()
        assertFalse(s.master); assertFalse(s.explained); assertTrue(s.enabled.isEmpty())
        com.zyke.message.domain.ai.AiFeature.entries.forEach { assertFalse(it.name, s.isOn(it)) }
        val repo = text("data/ai/AiSettingsRepositoryImpl.kt")
        assertTrue("getBoolean(KEY_MASTER, false)" in repo && "getBoolean(KEY_EXPLAINED, false)" in repo)
        val gate = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai/AiSettings.kt").readText()
        assertTrue("fun isOn(feature: AiFeature): Boolean = master && explained && feature in enabled" in gate)
    }

    @Test
    fun theDemoNeverBuildsOrCallsTheModel() {
        val hub = text("data/ai/AiHub.kt")
        assertTrue("if (demo) NoOnDeviceAi else GeminiNanoEngine(ModelEvidenceStore(context))" in hub)
        assertTrue("if (demo) InMemoryAiSettings() else AiSettingsRepositoryImpl(context)" in hub)
        assertTrue("if (demo) InMemoryAiCache() else PrefsAiCache(context)" in hub)
        assertTrue("AiHub(context, demo, appScope)" in text("di/AppContainer.kt"))
        val gate = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai/AiSettings.kt").readText()
        assertTrue("!context.demo && settings.isOn(feature) && (availability.canGenerate || context.modelWorked)" in gate)
        assertTrue("!context.demo && settings.isOn(feature) && (availability !is AiAvailability.Unsupported || context.modelWorked)" in gate)
        // The only place the engine is built is behind that check.
        assertEquals(1, kotlin(main).sumOf { Regex("""(?<!class )GeminiNanoEngine\(""").findAll(code(it)).count() })
    }

    @Test
    fun everySurfaceAsksTheGateBeforeShowing() {
        val surfaces = mapOf(
            "presentation/ai/AskUi.kt" to "rememberAiVisible(AiFeature.ASK)",
            "presentation/ai/MonthSentenceCard.kt" to "ai.knownMonthSentence",
            "presentation/ai/AiSuggestion.kt" to "rememberAiVisible(AiFeature.UNDERSTAND)",
            "presentation/dashboard/DashboardScreen.kt" to "rememberAiVisible(com.zyke.message.domain.ai.AiFeature.MONTH)"
        )
        surfaces.forEach { (file, needle) -> assertTrue("$file must check its gate", needle in text(file)) }
        val hub = text("data/ai/AiHub.kt")
        for (feature in listOf("UNDERSTAND", "CATEGORIES", "ASK", "MONTH")) assertTrue("AiHub gates $feature", "allowed(AiFeature.$feature)" in hub)
        assertTrue("summaries are background work and respect lite mode", "AiGate.backgroundAllowed(AiFeature.SUMMARIES" in hub)
        assertTrue("the batch respects lite mode and the charger", "AiGate.batchAllowed(" in hub)
    }

    @Test
    fun noPromptOrAnswerIsLogged() {
        val offenders = aiFiles.flatMap { f ->
            code(f).lines().mapIndexedNotNull { i, line ->
                if (Regex("""\b(Log\.[dviwe]\(|DevLog|DiagLog|BlackBox|println\(|Timber|printStackTrace|Toast)""").containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("no logging, toasts or traces in the AI code:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun ourCodeMakesNoNetworkCallForIt() {
        val offenders = aiFiles.filter { Regex("""java\.net|HttpURLConnection|okhttp|https?://|URL\(|WebView""").containsMatchIn(code(it)) }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun itsFilesAreInNoBackupAndNoExport() {
        val backup = text("data/backup/PrefsBackup.kt")
        val excluded = Regex("""EXCLUDED = setOf\(([^)]*)\)""").find(backup)!!.groupValues[1]
        assertEquals("ai_settings", Regex("""const val FILE = "([^"]+)"""").find(text("data/ai/AiSettingsRepositoryImpl.kt"))!!.groupValues[1])
        assertEquals("ai_cache", Regex("""const val FILE = "([^"]+)"""").find(text("data/ai/PrefsAiCache.kt"))!!.groupValues[1])
        assertTrue("\"ai_settings\"" in excluded && "\"ai_cache\"" in excluded)
        // Nothing that builds an export, a report, a log or a backup (other than the exclusion above) knows the AI.
        val spots = kotlin(File(main, "data/export")) + kotlin(File(main, "data/backup")).filter { it.name != "PrefsBackup.kt" } +
            kotlin(File(main, "data/diag")) + kotlin(File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/diagnostics")) +
            kotlin(File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/export")) + kotlin(File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/backup"))
        val offenders = spots.filter { Regex("""domain\.ai\b|data\.ai\b|AiHub|PrefsAiCache|ai_cache""").containsMatchIn(code(it)) }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
        assertTrue("Delete all my data removes every preference file", "deleteSharedPreferences" in text("data/DataWipe.kt"))
    }

    @Test
    fun theOldUnaskedPathStaysUnwired() {
        val container = text("di/AppContainer.kt")
        assertFalse("ModelTransactionParser(" in container)
        val observe = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/usecase/ObserveMessagesUseCase.kt").readText()
        assertTrue("ModelTransactionParser(NoModel)" in observe)
    }

    @Test
    fun theSettingsPageIsEqualWidthAndExplainsItself() {
        val page = text("presentation/ai/AiSettingsPage.kt")
        assertTrue("paired buttons stack on a narrow phone", page.split("ResponsiveRow").size >= 4 && ".cell()" in page)
        assertTrue("the one-time explanation", "Before you switch it on" in page && "no cloud fallback" in page && "can be wrong" in page && "Google's AI Core" in page)
        assertTrue("Demo mode shows labelled examples", "Made-up example" in page && "never uses the AI" in page)
        assertTrue("the actions the page promises", listOf("ModelWatch.downloadLabel", "Clear AI suggestions cache", "Try it", "Check my review pile").all { it in page })
        assertFalse("no looping progress", "CircularProgressIndicator" in page || "LinearProgressIndicator" in page)
        assertTrue("the page is a Settings page", "SettingsGroup.AI -> com.zyke.message.presentation.ai.AiSettingsContent()" in text("presentation/settings/SettingsScreen.kt"))
    }

    @Test
    fun theTextsNameTheNewDependencyAndWhatItDoes() {
        val privacy = File(repoRoot(), "docs/PRIVACY.md").readText()
        for (needle in listOf("On-device AI", "Gemini Nano", "AI Core", "genai-prompt", "GeminiNanoEngine", "ai_cache", "no cloud fallback")) assertTrue("PRIVACY.md lacks $needle", needle.lowercase() in privacy.lowercase())
        assertTrue("com.google.mlkit:genai-prompt" in com.zyke.message.domain.legal.OpenSourceNotices.coveredCoordinates)
        val legal = com.zyke.message.domain.legal.LegalText.fullText()
        assertTrue("On-device AI" in legal && "Gemini Nano" in legal && "AI Core" in legal)
        assertTrue("ML Kit" in File(repoRoot(), "NOTICE").readText())
        assertTrue("genai" in File(repoRoot(), "docs/LEGAL_NOTES.md").readText().lowercase() || "ML Kit GenAI" in File(repoRoot(), "docs/LEGAL_NOTES.md").readText())
    }

    @Test
    fun prefsAreReadNullSafely() {
        val repo = text("data/ai/AiSettingsRepositoryImpl.kt")
        assertTrue("getStringSet(KEY_FEATURES, null).orEmpty()" in repo)
        assertTrue("substringBefore('|').toLongOrNull()" in text("data/ai/PrefsAiCache.kt"))
    }

    @Test
    fun theModelCardNeverLeavesBothButtonsOffAndTryItIgnoresTheStatus() {
        val page = text("presentation/ai/AiSettingsPage.kt")
        assertTrue("ModelWatch.canDownload(availability, report)" in page)
        assertFalse("Check again must not depend on the status", Regex("""Check again[^\n]*enabled""").containsMatchIn(page))
        assertTrue("repeatOnLifecycle(Lifecycle.State.STARTED)" in page && "ModelWatch.POLL_MS" in page)
        assertTrue("TryItCard()" in page && "canTry" !in page)
        val hub = text("data/ai/AiHub.kt")
        assertTrue("ignoreStatus = true" in hub && "availability.value.canGenerate" !in hub.substringAfter("suspend fun tryIt").substringBefore("fun clearCache"))
        val engine = text("data/ai/GeminiNanoEngine.kt")
        assertTrue("ModelWatch.afterSuccessfulAnswer" in engine)
    }

    @Test
    fun aRecentAnswerBeatsTheStatusWordAndNothingStaysDownloadingForever() {
        val engine = text("data/ai/GeminiNanoEngine.kt")
        assertTrue("the engine keeps the proof and applies it", "evidence.save(at, took)" in engine && "ModelWatch.effective(" in engine)
        assertTrue("the stream-less cap is enforced", "ModelWatch.streamlessExpired(" in engine && "ModelWatch.STREAMLESS_CAP_MS" in engine)
        assertTrue("only a withdrawn model forgets the proof", "AiFailure.UNAVAILABLE) forgetProof()" in engine)
        val store = text("data/ai/ModelEvidenceStore.kt")
        assertTrue("the proof is two numbers in the ai_settings file", "AiSettingsRepositoryImpl.FILE" in store && "putLong(KEY_AT, atMs)" in store)
        val gateway = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai/AiGateway.kt").readText()
        assertTrue("features try when it answered recently", "ModelWatch.worked(engine.report.value" in gateway)
        val hub = text("data/ai/AiHub.kt")
        assertTrue("the context carries the proof", "modelWorked = ModelWatch.worked(report.value" in hub)
    }

    @Test
    fun theWarmUpIsOptionalQuietAndGated() {
        val hub = text("data/ai/AiHub.kt")
        val warm = hub.substringAfter("fun warmUp()").substringBefore("fun cancelWarmUp")
        assertTrue("ModelWatch.shouldWarmUp(" in warm && "quiet = true" in warm && "WARM_TOKENS" in warm)
        assertTrue("const val WARM_TOKENS = 2" in hub)
        val rule = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai/ModelReport.kt").readText()
        assertTrue("on && !context.demo && !context.lite" in rule)
        val page = text("presentation/ai/AiSettingsPage.kt")
        assertTrue("ai.warmUp()" in page && "ai.cancelWarmUp()" in page && "Warming up the model" in page)
    }

    @Test
    fun askZykeIsOnePageWithOneParserAndOneExecutorAndStaysBehindTheGate() {
        val ui = text("presentation/ai/AskUi.kt")
        val page = text("presentation/ai/AskZykePage.kt")
        val bubbles = text("presentation/ai/AskBubbles.kt")
        val chat = text("presentation/ai/AskChat.kt")
        assertFalse("the old sheet is gone", File(main, "presentation/ai/AskHomeSheet.kt").exists() || "AskResultPane" in ui + page + chat || "AskMoneySheet" in ui + page + chat)
        assertTrue("visible only through the same gate as Search's Ask (master and feature on, not Demo); Demo shows labelled examples", "rememberAiVisible(AiFeature.ASK)" in ui && "visible || ai.demo" in ui)
        assertTrue("one parser and one executor, both in the chat holder", "ai.ask(" in chat && "AskExecutor.run(" in chat && "ai.ask(" !in page + bubbles + ui && "AskExecutor" !in page + bubbles + ui)
        assertTrue("the answer text and figures come from the app", "AskAnswer.of(" in chat)
        assertTrue("a plain way out", "Use normal search" in bubbles && "Set up on-device AI" in bubbles && "Search messages for" in bubbles)
        assertTrue("the request before is passed so a follow-up can change part of it", "ai.ask(question, payees, today, previous)" in chat)
        assertTrue("Home and Search both open the page, Home only behind the gate", "askOpen && askShown" in text("presentation/home/HomeScreen.kt") && "AskZykePage(" in text("presentation/home/HomeScreen.kt") && "AskZykePage(" in ui)
        assertTrue("the field says Ask Zyke and the page is not saved", "\"Ask Zyke\"" in page && "Chats are not saved" in page)
        assertFalse("no library in the chat", "com.google.mlkit" in ui + page + bubbles + chat)
    }

    @Test
    fun theChatIsKeptInMemoryOnlyAndTheDemoNeverAsksTheModel() {
        val chat = code(File(main, "presentation/ai/AskChat.kt"))
        val screens = code(File(main, "presentation/ai/AskZykePage.kt")) + code(File(main, "presentation/ai/AskBubbles.kt")) + code(File(main, "presentation/ai/AskUi.kt"))
        val storage = Regex("""SharedPreferences|getSharedPreferences|openFileOutput|FileWriter|File\(|Room|DataStore|PrefsAiCache|AiCache|cache\.put|Serializable|savedState|rememberSaveable\([^)]*entries""")
        assertFalse("conversation text is never written to disk", storage.containsMatchIn(chat))
        assertFalse("the screens save no conversation either", Regex("""SharedPreferences|openFileOutput|DataStore|AiCache""").containsMatchIn(screens))
        assertTrue("the holder is in memory and Clear chat empties it", "mutableStateListOf<ChatEntry>()" in chat && "entries.clear()" in chat && "fun clear()" in chat)
        assertTrue("in Demo the chat holds only made-up examples and sends nothing", "if (question.isEmpty() || thinking || showingExamples) return" in chat && "AskExamples.build(" in chat)
        assertTrue("the examples say they are made up", "example = true" in chat && "Example only: made up, not from your messages." in text("presentation/ai/AskBubbles.kt"))
        assertTrue("nothing is logged", !Regex("""Log\.|println\(|Toast|Timber""").containsMatchIn(chat + screens))
        assertTrue("a request is the holder's and stops on Stop or Clear", "job?.cancel()" in chat && "fun stop()" in chat)
    }

    @Test
    fun askZykeFollowsTheHouseRules() {
        val files = listOf("AskUi.kt", "AskChat.kt", "AskBubbles.kt", "AskZykePage.kt").associateWith { code(File(main, "presentation/ai/$it")) }
        files.forEach { (name, c) ->
            assertFalse("$name: no labelled return in a composable lambda (D8)", Regex("""return@""").containsMatchIn(c))
            assertFalse("$name: Material's Text would not hide amounts", "androidx.compose.material3.Text" in c)
            assertFalse("$name: no tour anchor, the tour anchors stay once each", "tourAnchor" in c)
            assertFalse("$name: no spinner or looping bar", Regex("""CircularProgressIndicator|LinearProgressIndicator|rememberInfiniteTransition|infiniteRepeatable""").containsMatchIn(c))
        }
        val page = files.getValue("AskZykePage.kt")
        assertTrue("it is an AppPage, so Back and Escape close it and it rises with the keyboard", "AppPage(" in page && "scrollable = false" in page)
        assertTrue("haptics go through the funnel on send", "rememberHaptics()" in page && "haptics.tick()" in page && "performHapticFeedback" !in page)
        assertTrue("send is off while empty and becomes stop while thinking", "val canSend = draft.isNotBlank() && !thinking && !demo" in page && "if (thinking) \"Stop\" else \"Send\"" in page)
        assertTrue("single line growing to four", "MAX_INPUT_LINES = 4" in page && "ImeAction.Send" in page)
    }
}
