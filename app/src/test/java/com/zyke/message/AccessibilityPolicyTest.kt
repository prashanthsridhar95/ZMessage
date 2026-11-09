package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source scans that keep screen-reader and keyboard basics from slipping back. They are deliberately coarse (no parsing of
 * Kotlin): a decorative icon is allowed only where it is listed, with the reason, so a new unlabelled icon is a decision
 * somebody makes, not an accident.
 */
class AccessibilityPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private val presentation get() = File(appRoot(), "src/main/java/com/zyke/message/presentation")

    private fun sources(): List<Pair<String, List<String>>> = presentation.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(presentation).invariantSeparatorsPath to it.readLines().map { line -> line.substringBefore("//") } }.toList()

    /**
     * Icons that are only decoration: each sits inside a row, tab, tile or button whose own text (or whose own description)
     * is what a screen reader announces. Counts are per file; going over one needs a new line here with its reason.
     */
    private val decorative = mapOf(
        "search/SearchPage.kt" to 5,                       // close glyph in a chip that describes itself; search glyph, page icon, chevron and result icon inside labelled rows
        "common/FormInputs.kt" to 1,                       // calendar glyph beside the field's own label
        "common/UnderlineTabs.kt" to 1,                    // tab icon beside the tab's text
        "common/EmptyState.kt" to 1,                       // illustration above the title
        "common/PermissionGate.kt" to 1,                   // bullet icon beside its sentence
        "common/Buttons.kt" to 1,                          // chevron inside LinkButton, whose text is the label
        "common/SearchField.kt" to 1,                      // magnifier inside the search field
        "settings/SettingsScreen.kt" to 1,                 // icon of a settings row, whose title and summary are read
        "home/GoalsAndReminders.kt" to 1,                  // icon of a ShortcutTile, whose text is the label
        "home/AccountsUi.kt" to 1,                         // leading icon of a menu item, whose text is the label
        "AppRoot.kt" to 2,                                 // the two layers of a bottom-bar icon; the item has the label
        "messages/components/OtpCard.kt" to 1,             // copy glyph on a card that is described and clickable as a whole
        "messages/components/MessageCommon.kt" to 2,       // airline logo (its box clears semantics; the card speaks) and the small icon in IconCaption beside its text
        "messages/components/SpendSummaryRow.kt" to 1,     // chevron on a row that says what it opens
        "dashboard/components/DashboardSections.kt" to 2,  // chevrons on rows that say what they open
        "dashboard/components/ReportCardSection.kt" to 1   // chevron on the report card row
    )

    @Test
    fun anUnlabelledIconIsOnlyEverDecorative() {
        val found = sources().associate { (name, lines) -> name to lines.count { "contentDescription = null" in it } }.filterValues { it > 0 }
        found.forEach { (file, count) ->
            val allowed = decorative[file]
            assertTrue("$file has $count 'contentDescription = null' but only ${allowed ?: 0} are listed as decorative; label it, or list it with a reason", allowed != null && count <= allowed)
        }
    }

    @Test
    fun noUnlabelledIconSitsInAnIconButtonOrAClickableWithoutALabel() {
        sources().forEach { (file, lines) ->
            lines.forEachIndexed { i, line ->
                if ("contentDescription = null" !in line) return@forEachIndexed
                val before = lines.subList(maxOf(0, i - 4), i + 1).joinToString("\n")
                assertTrue("$file:${i + 1}: an IconButton must describe its icon", "IconButton(" !in before)
            }
        }
    }

    @Test
    fun everyIconButtonHasADescription() {
        sources().forEach { (file, lines) ->
            lines.forEachIndexed { i, line ->
                if ("IconButton(" !in line) return@forEachIndexed
                val after = lines.subList(i, minOf(lines.size, i + 8)).joinToString("\n")
                assertTrue("$file:${i + 1}: IconButton without a contentDescription", Regex("""contentDescription\s*=\s*"[^"]+"""").containsMatchIn(after))
            }
        }
    }

    @Test
    fun aClickableRowIsAnnouncedAsSomething() {
        // Every tappable thing says what it is (button, tab, switch ...). The two scrims behind a sheet and a page are the
        // only taps with no role: they are not controls, a tap on them is "close", and Back does the same.
        val scrims = setOf("common/PageHost.kt", "common/SheetHost.kt")
        sources().forEach { (file, lines) ->
            if (file in scrims || file == "common/FullBleed.kt") return@forEach
            lines.forEachIndexed { i, line ->
                if (Regex("""\.clickable\(""").containsMatchIn(line) && "role" !in line) {
                    val next = lines.subList(i, minOf(lines.size, i + 4)).joinToString(" ")
                    assertTrue("$file:${i + 1}: clickable without a role", "role" in next)
                }
            }
        }
    }

    @Test
    fun theTouchTargetOfASmallControlIsAtLeast48dp() {
        val forms = File(presentation, "common/Buttons.kt").readText()
        assertTrue("compact buttons keep a 48 dp target", "heightIn(min = 48.dp)" in forms && "40.dp else" !in forms)
        val flights = File(presentation, "flights/FlightUi.kt").readText()
        assertTrue("the flight card's Hide X is 48 dp", ".size(48.dp).clip(CircleShape)" in flights && "onClickLabel = \"Hide for today\"" in flights)
    }

    @Test
    fun pagesAndSectionsAreHeadingsAndEscapeGoesBack() {
        val pages = File(presentation, "common/PageHost.kt").readText()
        assertTrue("Modifier.heading()" in pages && "paneTitle" in pages)
        assertTrue("Key.Escape" in pages && "Key.Escape" in File(presentation, "common/SheetHost.kt").readText())
        assertTrue("heading()" in File(presentation, "dashboard/components/DashboardSections.kt").readText())
    }

    @Test
    fun theCountdownRingTellsTheTimeLeft() {
        val code = File(presentation, "home/CodeUi.kt").readText()
        assertEquals(1, Regex("""stateDescription = left""").findAll(code).count())
    }

    @Test
    fun theLastFieldOfAFormCanBeDoneAndTheNextOneMovesOn() {
        val forms = File(presentation, "common/Forms.kt").readText()
        assertTrue("imeAction: ImeAction = ImeAction.Next" in forms && "onDone" in forms)
    }
}
