package com.zyke.message.domain

import com.zyke.message.domain.search.GlobalSearch
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.domain.search.SearchSources
import com.zyke.message.domain.search.TextHighlight
import com.zyke.message.domain.settings.SettingsGroupId
import com.zyke.message.domain.settings.SettingsIndex
import com.zyke.message.domain.settings.SettingsLayout
import com.zyke.message.domain.settings.SettingsPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {

    /**
     * Every setting that existed before Settings was grouped (wave B5), by the id it has in the index. If a rearrangement
     * loses one, this fails; adding a setting means adding its id to the index, and here.
     */
    private val before = listOf(
        "appearance.theme", "appearance.colour", "appearance.icon", "appearance.haptics", "appearance.performance",
        "alerts.status", "alerts.morning", "alerts.buttons", "alerts.weekly", "alerts.headsup", "alerts.floor", "alerts.share", "alerts.widget",
        "money.salary", "money.savings", "money.fixed", "money.cycle",
        "budgets.categories", "budgets.suggestions", "budgets.move", "budgets.card",
        "markets.watch", "markets.close", "cards.list", "cards.add",
        "messages.logos", "messages.tags", "messages.rcs", "messages.health", "rules.categories", "rules.taught",
        "travel.flights", "travel.trains", "travel.checks",
        "data.auto", "data.backup", "data.export.transactions", "data.export.setup", "data.reset", "data.delete",
        "privacy.hide", "privacy.auth", "privacy.reveal", "privacy.screenshots", "privacy.lock", "privacy.pause",
        "help.tour", "help.demo", "help.legal", "help.licences", "about.version", "about.logs",
        "dev.log", "dev.checks", "dev.audit", "dev.simulate", "dev.off"
    )

    @Test
    fun noSettingHasDisappeared() {
        val ids = SettingsIndex.all.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertEquals("missing: ${before - ids.toSet()}", emptyList<String>(), before.filter { it !in ids })
    }

    @Test
    fun everyPageIsListedExactlyOnceOnTheFirstScreen() {
        val listed = SettingsLayout.groups.flatMap { it.second }
        assertEquals(SettingsPageId.entries.toSet(), listed.toSet())
        assertEquals("a page is listed twice", listed.size, listed.toSet().size)
        assertEquals("a group is listed twice", SettingsLayout.groups.size, SettingsLayout.groups.map { it.first }.toSet().size)
        assertEquals(SettingsGroupId.entries.toSet(), SettingsLayout.groups.map { it.first }.toSet())
    }

    @Test
    fun everyPageHoldsAtLeastOneSetting() {
        SettingsPageId.entries.forEach { page ->
            assertTrue("$page has no entry", SettingsIndex.all.any { it.page == page })
        }
    }

    @Test
    fun anEntryIsListedOnAPageOfItsOwnGroup() {
        val pagesOf = SettingsLayout.groups.toMap()
        SettingsIndex.all.forEach { e ->
            val own = pagesOf.getValue(e.group)
            // The version and logs live on the data page but are listed under Help; they are the only two.
            val exception = e.id == "about.version" || e.id == "about.logs"
            assertTrue("${e.id}: page ${e.page} is not under group ${e.group}", e.page in own || exception)
        }
    }

    @Test
    fun titlesAndSummariesAreShortEnoughForOneLine() {
        SettingsIndex.all.forEach {
            assertTrue("${it.id} title too long", it.title.length <= 40)
            assertTrue("${it.id} summary too long (${it.summary.length})", it.summary.length <= 60)
            assertFalse("${it.id} has no keywords", it.keywords.isEmpty())
        }
    }

    @Test
    fun developerEntriesAndDemoHiddenEntriesAreGated() {
        val normal = SettingsIndex.available(developer = false, demo = false)
        assertTrue(normal.none { it.developerOnly })
        assertTrue(normal.any { it.id == "data.delete" })
        val demo = SettingsIndex.available(developer = false, demo = true)
        assertTrue(demo.none { it.id == "data.delete" || it.id == "appearance.icon" || it.id == "data.backup" })
        val dev = SettingsIndex.available(developer = true, demo = false)
        assertTrue(dev.any { it.id == "dev.log" })
        assertTrue(SettingsIndex.search("log file", developer = false).none { it.entry.id == "dev.log" })
        assertTrue(SettingsIndex.search("log file", developer = true).any { it.entry.id == "dev.log" })
    }

    @Test
    fun searchFindsByTitleSummaryAndKeywordBestFirst() {
        val dark = SettingsIndex.search("dark").map { it.entry.id }
        assertEquals("appearance.theme", dark.first())
        val fingerprint = SettingsIndex.search("fingerprint").map { it.entry.id }
        assertTrue("privacy.auth" in fingerprint && "privacy.lock" in fingerprint)
        // A title that starts with the word comes before a keyword hit.
        val backup = SettingsIndex.search("backup").map { it.entry.id }
        assertTrue(backup.indexOf("data.backup") < backup.indexOf("data.delete").let { if (it < 0) Int.MAX_VALUE else it })
        assertTrue("data.auto" in backup)
    }

    @Test
    fun everyTypedWordMustMatch() {
        assertTrue(SettingsIndex.search("hide amounts").any { it.entry.id == "privacy.hide" })
        assertTrue(SettingsIndex.search("hide zebra").isEmpty())
        assertTrue(SettingsIndex.search("   ").isEmpty())
        assertTrue(SettingsIndex.search("").isEmpty())
    }

    @Test
    fun matchesCarryTheRangesToDrawBold() {
        val m = SettingsIndex.search("haptic").first { it.entry.id == "appearance.haptics" }
        assertEquals(listOf(0 until 6), m.titleRanges)
        val s = SettingsIndex.search("tick").first { it.entry.id == "appearance.haptics" }
        assertTrue(s.titleRanges.isEmpty())
        assertEquals(1, s.summaryRanges.size)
        assertEquals("tick", "A light tick on switches, a firmer one on deletes".substring(s.summaryRanges[0].first, s.summaryRanges[0].last + 1))
    }

    @Test
    fun theGlobalSearchListsSettingsAsTheirOwnGroup() {
        val result = GlobalSearch.search("hide amounts", SearchSources(), limit = 10)
        val group = result.groups.first { it.kind == SearchKind.SETTING }
        assertEquals("Settings", group.kind.label)
        assertEquals("privacy.hide", group.hits.first().id)
        assertEquals(group.hits.size, group.total)
        // One letter is not worth a list of settings.
        assertTrue(GlobalSearch.search("h", SearchSources()).groups.none { it.kind == SearchKind.SETTING })
        // Developer settings only in developer mode.
        assertTrue(GlobalSearch.search("snapshot", SearchSources()).groups.none { it.kind == SearchKind.SETTING })
        assertTrue(GlobalSearch.search("snapshot", SearchSources(developerMode = true)).groups.any { it.kind == SearchKind.SETTING })
    }

    @Test
    fun highlightRangesMergeAndIgnoreCase() {
        assertEquals(emptyList<IntRange>(), TextHighlight.ranges("Swiggy", emptyList()))
        assertEquals(emptyList<IntRange>(), TextHighlight.ranges("", listOf("a")))
        assertEquals(emptyList<IntRange>(), TextHighlight.ranges("Swiggy", listOf("")))
        assertEquals(listOf(0 until 3), TextHighlight.ranges("Swiggy", listOf("SWI")))
        // Overlapping and touching matches become one range.
        assertEquals(listOf(0 until 6), TextHighlight.ranges("Swiggy", listOf("swig", "iggy")))
        assertEquals(listOf(0 until 3, 4 until 7), TextHighlight.ranges("abc abc", listOf("abc")))
        assertEquals(listOf(0 until 2, 4 until 6), TextHighlight.ranges("ab  ab", listOf("ab")))
        assertEquals(listOf("food", "swiggy"), TextHighlight.words("  food  swiggy "))
    }
}
