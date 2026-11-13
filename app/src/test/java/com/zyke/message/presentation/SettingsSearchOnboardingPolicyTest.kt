package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave B5 (docs/DESIGN.md: Settings, Search, Onboarding, Empty/error/loading voice): Settings is grouped and searchable and
 * nothing in it was lost, every destructive confirm names what is lost and buzzes, search moves between its states through
 * the motion funnel, the onboarding pages share one hero, and the empty/error/loading pages speak in one voice.
 */
class SettingsSearchOnboardingPolicyTest {

    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private val presentation get() = File(appRoot(), "src/main/java/com/zyke/message/presentation")
    private fun text(rel: String) = File(presentation, rel).readText()
    private fun code(rel: String): List<String> = File(presentation, rel).readLines().map { it.substringBefore("//") }

    private fun allSources(): List<Pair<String, String>> = presentation.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(presentation).invariantSeparatorsPath to it.readText() }.toList()

    @Test
    fun theSettingsPagesOfTheDomainAreTheAppsPages() {
        val domain = File(appRoot(), "../domain/src/main/kotlin/com/zyke/message/domain/settings/SettingsIndex.kt").readText()
        val pages = domain.substringAfter("enum class SettingsPageId").substringBefore("\n}").let { Regex("""(\w+)\("""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val app = text("settings/SettingsScreen.kt").substringAfter("enum class SettingsGroup(").substringBefore("\n}").let { Regex("""(\w+)\("""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        assertTrue("no pages found", pages.size >= 10)
        assertEquals("SettingsPageId (domain) and SettingsGroup (app) must list the same pages", pages, app)
    }

    @Test
    fun settingsIsGroupedAndSearchableAndKeepsEverySettingReachable() {
        val screen = text("settings/SettingsScreen.kt")
        val hub = text("settings/SettingsHub.kt")
        assertTrue("the first screen is the hub", "SettingsHub(" in screen && "SettingsList(" !in screen)
        assertTrue("the hub lists the domain's groups", "SettingsLayout.visible(developer)" in hub)
        assertTrue("the hub has a search field that does not steal focus", "SearchField(" in hub && "autoFocus = false" in hub)
        assertTrue("results are drawn from the index with the words in bold", "SettingsIndex.search(" in hub && "highlighted(" in hub)
        assertTrue("no match says so, with a way out", "No matches for" in hub && "Clear search" in hub)
        assertTrue("rows dip on press and the first groups enter once", "tappable(" in hub && "enterOnce(gate" in hub)
        assertTrue("the developer group only appears in developer mode", "SettingsLayout.visible(developer)" in hub && "SettingsGroup.DEVELOPER" in screen)
        assertTrue("a search result opens the page that holds the setting", "SettingsDeepLink.pending" in screen && "SettingsDeepLink.pending" in text("MainActivity.kt"))
        // The rows are title + one line: no multi-line captions in a hub row.
        assertTrue("a hub row is one line", Regex("""maxLines = 1""").findAll(hub).count() >= 2)
    }

    @Test
    fun longExplanationsLiveBehindLearnMoreNotInCaptions() {
        val toggle = text("settings/SettingsScreen.kt").substringAfter("internal fun ToggleRow(").substringBefore("\n}\n")
        assertTrue("a switch row has a one-line summary (two lines at most)", "Caption(description, maxLines = 2)" in toggle && "learnMore" in toggle)
        // The sheet that deletes everything names what is lost, and keeps its room (maxLines = 8).
        val tooLong = listOf("settings/SettingsScreen.kt", "settings/SettingsHub.kt").flatMap { rel ->
            code(rel).mapIndexedNotNull { i, line ->
                if (Regex("""maxLines = ([3-9]|\d\d)""").containsMatchIn(line) && "maxLines = 8" !in line) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("A caption longer than two lines belongs behind LearnMore:\n" + tooLong.joinToString("\n"), tooLong.isEmpty())
        val learn = text("common/LearnMore.kt")
        assertTrue("Learn more opens with the card-open motion and is a 48 dp button", "AppearVisibility(" in learn && "heightIn(min = 48.dp)" in learn)
    }

    @Test
    fun everyDestructiveConfirmNamesWhatIsLostAndBuzzes() {
        val dialog = text("common/ConfirmDialog.kt")
        assertTrue("the confirming tap gives the firmer buzz", "haptics.reject()" in dialog)
        assertTrue("equal-width Cancel and Confirm", "ResponsiveRow(" in dialog && dialog.split(".cell()").size >= 3)
        assertTrue("destructive is styled by the theme's error colour", "ButtonStyle.DANGER" in dialog && "scheme.error" in text("common/Buttons.kt"))
        val screen = text("settings/SettingsScreen.kt")
        assertTrue("Reset marks and budgets asks first", "ConfirmDialog(" in screen && "Reset marks and budgets?" in screen)
        assertTrue("Delete all my data buzzes before wiping", Regex("""haptics\.reject\(\)\s*com\.zyke\.message\.data\.DataWipe\.wipe""").containsMatchIn(screen))
        assertTrue("removing a card asks first", "ConfirmDialog(" in text("settings/CardDialog.kt") && "AlertDialog(" !in text("settings/CardDialog.kt"))
        assertTrue("replacing the setup from a backup asks first", "ConfirmDialog(" in text("backup/RestoreFlow.kt") && "Replace what you have now?" in text("backup/RestoreFlow.kt"))
        assertTrue("clearing the code lists asks first", "Clear the list of texts seen?" in text("settings/CodeSharingUi.kt"))
        assertTrue("a sheet's Remove / Delete gives the buzz", "haptics.reject()" in text("common/Forms.kt"))
        listOf("settings/RulesUi.kt", "settings/CustomCategoriesSection.kt").forEach { assertTrue("$it: deleting buzzes", "haptics.reject()" in text(it)) }
        assertTrue("forgetting a payee rule asks first", "ConfirmDialog(" in text("settings/RulesUi.kt") && "Payee rules" in text("settings/RulesUi.kt"))
        assertTrue("no bare AlertDialog is left in Settings' own screen", "AlertDialog(" !in screen)
    }

    @Test
    fun searchMovesBetweenItsStatesAndShowsSettings() {
        val search = text("search/SearchPage.kt")
        assertTrue("idle, loading, results, empty and error crossfade", "CrossfadeContent(stage" in search && "enum class Stage" in search)
        assertTrue("the skeleton is the shared still one, and only after a slow search", "LoadingState(" in search && "SLOW_MS" in search)
        assertTrue("an error is a shared ErrorState with a retry", "ErrorState(" in search && "onRetry" in search)
        assertTrue("results that come and go use item motion", "itemMotion()" in search)
        assertTrue("recent searches are equal chips with a clear action", "RecentChips(" in search && "FlowRow(" in search && "\"Clear all\"" in search)
        assertTrue("the empty state names the query", "No matches for" in search)
        assertTrue("rows dip on press", Regex("""tappable\(""").findAll(search).count() >= 5 && Regex("""\.clickable\(""").findAll(search).count() <= 1)
        assertTrue("the keyboard's action is Search", "ImeAction.Search" in text("common/SearchField.kt"))
        assertTrue("typed words are drawn bold from the domain's ranges", "TextHighlight.ranges" in text("search/Highlight.kt"))
        assertTrue("Settings is a result group", "SearchKind.SETTING" in search && "developerMode" in search)
    }

    @Test
    fun onboardingPagesShareOneHeroWithOneAskEach() {
        val hero = text("common/OnboardingHero.kt")
        assertTrue("icon tile, headline, promise, full-width primary, secondary text button", "StateIcon(" in hero && "TypeRole.headline" in hero && "AppButton(primaryLabel, onPrimary, Modifier.fillMaxWidth())" in hero && "ButtonStyle.TEXT" in hero)
        assertTrue("a gentle one-shot entrance", "enterOnce(gate" in hero && "rememberEnterGate()" in hero)
        assertTrue("step dots have a spoken form and animate through the funnel", "steps.spoken" in hero && "motion.tween(Motion.QUICK_MS)" in hero)
        val gate = text("common/PermissionGate.kt")
        assertTrue("the permission page is the hero", "OnboardingHero(" in gate && "PermissionStep" in gate)
        assertTrue("the welcome-back page is the same hero and keeps the terms line",
            "StateIcon(" in text("backup/WelcomeBack.kt") && "By continuing you agree to the Terms and Privacy." in text("backup/WelcomeBack.kt"))
        assertTrue("the tour card is on tokens", "AppShapes.card" in text("tour/TourHost.kt") && "Spacing.xl" in text("tour/TourHost.kt"))
    }

    @Test
    fun aTourAnchorLeavingCannotClearOneArriving() {
        val registry = text("tour/TourRegistry.kt")
        assertTrue("only the registered node clears itself", "if (registry.requesters[id] === requester)" in registry)
        assertTrue("only the registered node reports its place", "registry.requesters[id] === requester && registry.bounds[id] != rect" in registry)
    }

    @Test
    fun emptyErrorAndLoadingSpeakInOneVoice() {
        listOf("common/EmptyState.kt", "common/ErrorState.kt", "common/PermissionGate.kt", "common/OnboardingHero.kt", "common/LoadingState.kt", "common/ConfirmDialog.kt").forEach { rel ->
            val raw = code(rel).filter { !it.trimStart().startsWith("import ") }.filter { Regex("""(?<![\d.])(?!0\.dp)\d+\.dp""").containsMatchIn(it) }
            // Fixed sizes of a tile or a touch target are allowed: 48 (touch) and the skeleton tile; spacing and corners are tokens.
            val spacing = raw.filter { Regex("""(padding|spacedBy|RoundedCornerShape)\(""").containsMatchIn(it) }
            assertTrue("$rel has raw dp spacing or corners; use Spacing / Radius:\n" + spacing.joinToString("\n"), spacing.isEmpty())
        }
        assertTrue("an error shares the empty state's tile", "StateIcon(icon)" in text("common/ErrorState.kt"))
        assertTrue("the loading page is the still skeleton", "fadeInOnce()" in text("common/LoadingState.kt") && "SkeletonBlock(" in text("common/LoadingState.kt"))
        // Every empty state written with plain strings: a short title and one line of why.
        val bundledElsewhere = setOf("home/SubscriptionsPage.kt", "home/PaidBillsPage.kt", "home/BillsPage.kt")
        val call = Regex("""EmptyState\(\s*(?:title\s*=\s*)?"((?:[^"\\]|\\.)*)"\s*,\s*(?:body\s*=\s*)?"((?:[^"\\]|\\.)*)"""")
        val offenders = allSources().filter { (rel, _) -> !rel.startsWith("bills/") && rel !in bundledElsewhere }.flatMap { (rel, src) ->
            call.findAll(src).mapNotNull { m ->
                val title = m.groupValues[1]
                val body = m.groupValues[2]
                when {
                    title.length > 40 -> "$rel: title too long: $title"
                    title.endsWith(".") -> "$rel: no full stop on a title: $title"
                    body.length > 100 -> "$rel: body longer than one line (${body.length}): $body"
                    else -> null
                }
            }.toList()
        }
        assertTrue("Empty states speak in one voice (docs/DESIGN.md):\n" + offenders.joinToString("\n"), offenders.isEmpty())
        val tabs = text("common/UnderlineTabs.kt")
        assertTrue("underline tabs slide their line on the tab token", "SlidingTabIndicator(positions, current)" in tabs)
    }
}
