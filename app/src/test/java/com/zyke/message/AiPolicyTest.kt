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
        assertTrue(File(main, "data/ai/GeminiNanoEngine.kt").readText().contains("class GeminiNanoEngine : OnDeviceAi"))
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
        val offenders = kotlin(File(main, "notifications")).plus(kotlin(File(main, "data/sms"))).filter {
            Regex("""\bAiHub\b|domain\.ai\b|data\.ai\b|\.container\.ai\b|presentation\.ai\b""").containsMatchIn(code(it))
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
        assertTrue("if (demo) NoOnDeviceAi else GeminiNanoEngine()" in hub)
        assertTrue("if (demo) InMemoryAiSettings() else AiSettingsRepositoryImpl(context)" in hub)
        assertTrue("if (demo) InMemoryAiCache() else PrefsAiCache(context)" in hub)
        assertTrue("AiHub(context, demo, appScope)" in text("di/AppContainer.kt"))
        val gate = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai/AiSettings.kt").readText()
        assertTrue("!context.demo && settings.isOn(feature) && availability.canGenerate" in gate)
        assertTrue("!context.demo && settings.isOn(feature) && availability !is AiAvailability.Unsupported" in gate)
        // The only place the engine is built is behind that check.
        assertEquals(1, kotlin(main).sumOf { Regex("""GeminiNanoEngine\(\)""").findAll(code(it)).count() })
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
        assertTrue("the actions the page promises", listOf("Download model", "Clear AI suggestions cache", "Try it", "Check my review pile").all { it in page })
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
}
