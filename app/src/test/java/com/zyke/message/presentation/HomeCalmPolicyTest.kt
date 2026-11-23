package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps Home calm (docs/DESIGN.md, "Home"): few blocks, one hero, the removed blocks behind Customize Home, and each of them still
 * reachable from another place.
 */
class HomeCalmPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private val main get() = File(appRoot(), "src/main/java/com/zyke/message")
    private fun text(rel: String) = File(main, rel).readText()
    private val home get() = text("presentation/home/HomeScreen.kt")
    private val summary get() = home.substringAfter("private fun SummaryPage(")

    /** The keys of the lazy items of the summary page, with the three lines above each (where a guard would be). */
    private fun items(): List<Pair<String, String>> {
        val lines = summary.lines()
        return lines.mapIndexedNotNull { i, line ->
            Regex("""item\(key = "([^"]+)"\)""").find(line)?.let { it.groupValues[1] to lines.subList(maxOf(0, i - 3), i + 1).joinToString("\n") }
        }
    }

    @Test fun theCalmHomeHasAtMostSixBlocks_andFewerThanTwelveInAnyLayout() {
        val items = items()
        val keys = items.map { it.first }
        assertEquals("keys are unique", keys.size, keys.toSet().size)
        assertTrue("any layout stays under 13 blocks: $keys", keys.size <= 12)
        // The blocks in the calm layout: not behind a switch that is off by default, not a one-off (tour banner, blank state).
        val offByDefault = setOf("market", "plan-tiles", "plans", "quickadds")
        val oneOff = setOf("tour-banner", "blank")
        val calm = keys.filter { it !in offByDefault && it !in oneOff }
        assertTrue("the calm layout composes at most six blocks: $calm", calm.size <= 6)
    }

    @Test fun theRemovedBlocksAreBehindTheirLayoutSwitch() {
        val guard = mapOf("market" to "MARKET", "plan-tiles" to "SHORTCUTS", "plans" to "GOALS", "quickadds" to "QUICK_ADDS")
        val items = items().toMap()
        for ((key, section) in guard) {
            val around = items[key] ?: error("$key is gone from Home; it should still exist behind Customize Home")
            assertTrue("$key must be guarded by HomeSection.$section", "HomeSection.$section" in around)
        }
        val off = File(appRoot(), "../domain/src/main/kotlin/com/zyke/message/domain/home/HomeLayout.kt").readText()
        for (s in listOf("MARKET", "GOALS", "SHORTCUTS", "QUICK_ADDS")) assertTrue("$s is off by default", Regex("""$s\(.*, false\)""").containsMatchIn(off))
        for (s in listOf("BUDGET", "ACTIVITY", "DECK")) assertTrue("$s is on by default", Regex("""$s\(.*, true\)""").containsMatchIn(off))
    }

    @Test fun theOldSeparateCardsAreGone_andNothingOnHomeMovesItsOutline() {
        for (f in File(main, "presentation/home").listFiles().orEmpty().filter { it.extension == "kt" }) {
            val code = f.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            assertFalse("${f.name} must not use a moving outline", code.any { "movingOutline" in it || "movingBorder" in it })
        }
        for (gone in listOf("key = \"priority\"", "key = \"next\"", "ReviewCard(", "FocusCard(", "TourAnchor.HOME_NEXT_DUE", "TourAnchor.HOME_CODES")) {
            assertFalse("$gone is merged into Today", gone in home)
        }
        assertTrue("one Today card", Regex("""item\(key = "focus"\)""").containsMatchIn(home) && "HomeFocus.pick(" in home)
    }

    @Test fun everythingRemovedStaysOneTapAwayElsewhere() {
        val bills = text("presentation/home/BillsPage.kt")
        for (label in listOf("Calendar", "Goals", "Reminders", "Can I afford it?", "Found in your messages")) {
            assertTrue("Bills, Plan ahead has $label", "PlanAheadRow(\"$label\"" in bills)
        }
        for (intent in listOf("Intent.OpenAgenda", "Intent.OpenGoals", "Intent.OpenReminders", "Intent.OpenQuickAdds")) assertTrue(intent, intent in bills)
        assertTrue("the market watch opens from its settings", "MarketPage(onBack = { listOpen = false })" in text("presentation/markets/MarketSettings.kt"))
        assertTrue("the review page stays reachable when the deck is off", "reviewCount > 0" in text("presentation/home/HomeDeck.kt"))
        assertTrue("Settings has a way in", "HomeLayoutEntry()" in text("presentation/settings/SettingsScreen.kt"))
        assertTrue("and so has Home's footer", "HomeFooter(" in home && "HomeLayoutPage" in home)
    }

    @Test fun theLayoutIsStoredLikeEveryOtherSetting() {
        assertTrue(text("di/AppContainer.kt").contains("HomeLayoutRepositoryImpl(context)"))
        assertTrue(text("data/repository/HomeLayoutRepositoryImpl.kt").contains("\"home_layout\""))
        assertFalse("the layout travels in a backup", Regex("""EXCLUDED = setOf\([^)]*home_layout""").containsMatchIn(text("data/backup/PrefsBackup.kt")))
        val page = text("presentation/home/HomeLayoutPage.kt")
        assertTrue("one switch per section", "HomeSection.entries.forEach" in page)
        assertTrue("a reset button", "Reset to calm layout" in page)
    }
}
