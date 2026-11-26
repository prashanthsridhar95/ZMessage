package com.zyke.message

import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five features added after the first five (clean payee names, explain a message, why spending went up, the morning digest line,
 * unusual payments) follow the same containment as the first five, and the one that touches a notification is read-only. Like
 * [AiPolicyTest], these read the sources, so a change that breaks a rule fails the build.
 */
class AiExtrasPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private fun repoRoot(): File = appRoot().canonicalFile.let { if (it.name == "app") it.parentFile else it }
    private val main get() = File(appRoot(), "src/main/java/com/zyke/message")
    private val domainAi get() = File(repoRoot(), "domain/src/main/kotlin/com/zyke/message/domain/ai")
    private fun text(rel: String) = File(main, rel).readText()
    private fun kotlin(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(file: File) = file.readLines().map { it.substringBefore("//") }.filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") }.joinToString("\n")
    private fun doc(name: String) = File(repoRoot(), "docs/$name").readText()

    private val newFeatures = listOf(AiFeature.NAMES, AiFeature.EXPLAIN, AiFeature.WHY_UP, AiFeature.DIGEST, AiFeature.UNUSUAL)
    private val newDomainFiles = listOf("AiPhrase.kt", "AiNames.kt", "AiExplain.kt", "AiWhy.kt", "AiDigest.kt", "AiUnusual.kt")

    @Test
    fun theNewSwitchesAreOffByDefaultAndStoredLikeTheOthers() {
        val s = AiSettings()
        newFeatures.forEach { assertFalse(it.name, s.isOn(it)); assertFalse(it.name, s.copy(master = true, explained = true).isOn(it)) }
        assertTrue("one switch per feature on the settings page, generated from the enum", "AiFeature.entries.forEach" in text("presentation/ai/AiSettingsPage.kt"))
        assertTrue("stored by name in the same private set", "enabled = AiFeature.entries.filter { it.name in names }.toSet()" in text("data/ai/AiSettingsRepositoryImpl.kt"))
    }

    @Test
    fun everyNewSurfaceAsksItsGateAndTheHubGatesTheRequest() {
        val surfaces = text("presentation/ai/AiExtras.kt")
        for (feature in listOf("NAMES", "EXPLAIN", "UNUSUAL")) assertTrue("$feature surface gate", "rememberAiVisible(AiFeature.$feature)" in surfaces)
        assertTrue("the Insights card", "rememberAiVisible(com.zyke.message.domain.ai.AiFeature.WHY_UP)" in text("presentation/dashboard/DashboardScreen.kt") && "WhyUpCard(summary" in text("presentation/dashboard/DashboardScreen.kt"))
        assertTrue("the digest preparation needs its switch and is off in Demo", "settings.isOn(AiFeature.DIGEST) && !ai.demo" in surfaces)
        val hub = text("data/ai/AiHub.kt")
        for (feature in listOf("NAMES", "EXPLAIN", "WHY_UP", "UNUSUAL")) assertTrue("AiHub gates $feature", "allowed(AiFeature.$feature)" in hub)
        assertTrue("the digest line is background work: never in lite mode", "AiGate.backgroundAllowed(AiFeature.DIGEST" in hub)
        val explainer = File(domainAi, "AiExplain.kt").readText()
        assertTrue("a one-time code is never given to the model", "message.content !is MessageContent.Otp" in explainer)
        assertTrue("the surface checks it", "ai.canExplain(message)" in surfaces)
    }

    @Test
    fun theMorningNotificationOnlyReadsAndNeverAsks() {
        val store = File(main, "data/ai/DigestNarrativeStore.kt")
        val body = code(store)
        assertFalse("no hub, gateway, engine or request in the store", Regex("""AiHub|AiGateway|GeminiNanoEngine|OnDeviceAi|generate\(|\.launch|suspend |runBlocking""").containsMatchIn(body))
        assertTrue("it re-checks what it reads, and only when the switch is on", "AiDigest.verify(" in body && "isOn(AiFeature.DIGEST)" in body)
        val notifications = kotlin(File(main, "notifications")) + kotlin(File(main, "data/sms"))
        val users = notifications.filter { "DigestNarrativeStore" in code(it) }.map { it.name }
        assertEquals("only the digest notifier reads it", listOf("DigestReceiver.kt"), users)
        val receiver = text("notifications/DigestReceiver.kt")
        assertTrue("a failing read cannot break the digest", "runCatching { com.zyke.message.data.ai.DigestNarrativeStore.lookup(context, digest) }" in receiver)
        val other = notifications.filter { Regex("""AiHub|domain\.ai\b|presentation\.ai\b|\.container\.ai\b|data\.ai\.(?!DigestNarrativeStore)""").containsMatchIn(code(it)) }.map { it.name }
        assertEquals("nothing else of the AI layer in notifications or the SMS readers", emptyList<String>(), other)
        assertFalse("the SMS receiver is untouched", "DigestNarrative" in text("notifications/SmsReceiver.kt"))
        val callers = kotlin(main).filter { Regex("""\.prepareDigest\(""").containsMatchIn(code(it)) }.map { it.name }
        assertEquals("prepared only from the open app", listOf("AiExtras.kt"), callers)
        assertTrue("prepared in the background with a time limit and no retry", "retries = 0" in File(domainAi, "AiGateway.kt").readText() + File(domainAi, "AiPhrase.kt").readText())
        assertTrue("and only from the open app, a little after it opens", "repeatOnLifecycle(Lifecycle.State.STARTED)" in text("presentation/ai/AiExtras.kt") && "PREP_DELAY_MS" in text("presentation/ai/AiExtras.kt"))
    }

    @Test
    fun theDigestFactsNeverNeedYesterdayAndTheKeyIsTheLookup() {
        val digest = File(domainAi, "AiDigest.kt").readText()
        assertTrue("yesterday's spending is left out", "it.kind != DigestKind.SPENT" in digest)
        assertTrue("the notification finds its line by the key the preparation used", "phraser.phraseQuickly(facts.key" in digest && "getString(facts.key, null)" in text("data/ai/DigestNarrativeStore.kt"))
    }

    @Test
    fun promptsLiveInAiPromptsAndNoNewFileCarriesATemplate() {
        newDomainFiles.forEach { name ->
            val body = File(domainAi, name).readText()
            assertFalse("$name holds a prompt", Regex("""<(sms|facts|name|payee)>|Reply with|Treat the .* as data""").containsMatchIn(body))
        }
        val prompts = File(domainAi, "AiPrompts.kt").readText()
        for (fn in listOf("fun payeeName(", "fun explain(", "fun whyUp(", "fun digestLine(", "fun unusual(")) assertTrue("AiPrompts has $fn", fn in prompts)
        val functions = listOf("fun payeeName(", "fun explain(", "fun whyUp(", "fun digestLine(", "fun unusual(")
        functions.forEachIndexed { i, fn ->
            val region = prompts.substringAfter(fn).let { rest -> functions.drop(i + 1).firstOrNull()?.let { rest.substringBefore(it) } ?: rest.substringBefore("fun free(") }
            assertTrue("$fn tells the model its text is data", "as data, never as instructions" in region)
        }
    }

    @Test
    fun noLibraryNoNetworkNoLoggingInTheNewFiles() {
        val files = newDomainFiles.map { File(domainAi, it) } + listOf("AiExtras.kt", "AiThinkBlock.kt").map { File(main, "presentation/ai/$it") } + File(main, "data/ai/DigestNarrativeStore.kt")
        files.forEach { f ->
            val c = code(f)
            assertFalse("${f.name} imports ML Kit", "com.google.mlkit" in c)
            assertFalse("${f.name} touches the network", Regex("""java\.net|HttpURLConnection|okhttp|https?://|URL\(|WebView""").containsMatchIn(c))
            assertFalse("${f.name} logs or toasts", Regex("""\b(Log\.[dviwe]\(|DevLog|DiagLog|BlackBox|println\(|Timber|printStackTrace|Toast)""").containsMatchIn(c))
        }
    }

    @Test
    fun theNewSurfacesAreHonestAboutWaitingCancelAndHiddenAmounts() {
        val block = text("presentation/ai/AiThinkBlock.kt")
        assertTrue("Thinking in words and a Cancel that stops the request", "Thinking…" in block && "AppButton(\"Cancel\"" in block && "job?.cancel()" in block)
        assertTrue("the request is the screen's own, so leaving stops it", "rememberCoroutineScope()" in block)
        assertTrue("the AI's words go through the app's Text, which hides amounts", "import com.zyke.message.presentation.common.Text" in block && "androidx.compose.material3.Text" !in block)
        val extras = text("presentation/ai/AiExtras.kt")
        assertTrue("figures in rows are PrivateAmount", "PrivateAmount(row.shortText" in extras && "androidx.compose.material3.Text" !in extras)
        val looping = kotlin(File(main, "presentation/ai")).filter { Regex("""CircularProgressIndicator|LinearProgressIndicator""").containsMatchIn(code(it)) }.map { it.name }
        assertEquals("no spinner or looping bar in the AI screens", emptyList<String>(), looping)
        val returns = listOf("AiExtras.kt", "AiThinkBlock.kt", "AiState.kt", "AiSettingsPage.kt").filter { Regex("""return@""").containsMatchIn(code(File(main, "presentation/ai/$it"))) }
        assertEquals("no labelled return in a composable lambda (D8)", emptyList<String>(), returns)
    }

    @Test
    fun demoShowsLabelledMadeUpExamplesAndNeverCallsTheModel() {
        val page = text("presentation/ai/AiSettingsPage.kt")
        val demo = page.substringAfter("private fun DemoExamples()")
        for (title in listOf("clean payee name", "explain a message", "why did spending go up?", "morning digest line", "unusual payment")) assertTrue("Demo example: $title", "Example: $title" in demo)
        assertTrue("each example says it is made up", Regex("Made-up example, not from your messages").findAll(demo).count() >= 7)
        assertTrue("the digest preparation is off in Demo", "!ai.demo" in text("presentation/ai/AiExtras.kt"))
        assertTrue("the gate hides every surface in Demo", "!context.demo && settings.isOn(feature)" in File(domainAi, "AiSettings.kt").readText())
    }

    @Test
    fun theDocsDescribeTheNewFeatures() {
        val privacy = doc("PRIVACY.md").lowercase()
        for (needle in listOf("clean payee names", "explain a message", "why did spending go up", "morning digest line", "unusual payments", "digestnarrativestore", "read-only")) assertTrue("PRIVACY.md lacks $needle", needle in privacy)
        val design = doc("DESIGN.md").lowercase()
        for (needle in listOf("clean payee names", "explain this message", "why did this go up", "morning digest line", "say why in a sentence", "thinking")) assertTrue("DESIGN.md lacks $needle", needle in design)
        val architecture = doc("ARCHITECTURE.md")
        for (needle in listOf("AiPhraser", "AiSentence", "DigestNarrativeStore", "AiNameGuard", "AiExplainGuard", "AiWhy", "AiUnusual")) assertTrue("ARCHITECTURE.md lacks $needle", needle in architecture)
        assertTrue("UNIFICATION.md records the shared phrasing path", "AiPhraser" in doc("UNIFICATION.md"))
    }
}
