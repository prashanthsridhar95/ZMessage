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
        "search/SearchPage.kt" to 4,                       // close glyph in a chip that describes itself; search glyph, page icon, chevron and result icon inside labelled rows
        "common/FormInputs.kt" to 1,                       // calendar glyph beside the field's own label
        "common/UnderlineTabs.kt" to 1,                    // tab icon beside the tab's text
        "common/EmptyState.kt" to 1,                       // illustration above the title
        "common/PermissionGate.kt" to 1,                   // bullet icon beside its sentence
        "common/CardFace.kt" to 1,                         // the network's mark; the card is one button with one spoken sentence (bank, network, kind, last four) and clears its pieces
        "common/HeaderRegion.kt" to 1,                     // the icon in an ActionCircleRow circle; the label under it (or the action's description) is what is read
        "common/Buttons.kt" to 1,                          // chevron inside LinkButton, whose text is the label
        "common/SearchField.kt" to 1,                      // magnifier inside the search field
        "settings/SettingsHub.kt" to 2,                    // the icon tile and the chevron of a settings row, whose title and summary are read
        "home/GoalsAndReminders.kt" to 1,                  // icon of a ShortcutTile, whose text is the label
        "home/AccountsUi.kt" to 1,                         // leading icon of a menu item, whose text is the label
        "AppRoot.kt" to 2,                                 // the two layers of a bottom-bar icon; the item has the label
        "messages/components/SwipeActions.kt" to 1,         // the icon beside its label behind a swiped row; the row offers the same actions to a screen reader
        "messages/components/OtpCard.kt" to 1,             // copy glyph on a card that is described and clickable as a whole
        "messages/components/MessageCommon.kt" to 2,       // airline logo (its box clears semantics; the card speaks) and the small icon in IconCaption beside its text
        "trains/TrainParts.kt" to 1,                       // the train glyph on its tile (the card speaks as a whole)
        "trains/ManualTrainUi.kt" to 1,                    // the flight or train icon in a trip-choice row, whose title and caption are read
        "messages/components/SpendSummaryRow.kt" to 1,     // chevron on a row that says what it opens
        "dashboard/components/DashboardSections.kt" to 2,  // chevrons on rows that say what they open
        "dashboard/components/ReportCardSection.kt" to 1,  // chevron on the report card row
        "dashboard/components/InsightKit.kt" to 1          // the one Chevron of the Insights tab, at the end of a tile that says what it opens
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
        val trains = File(presentation, "trains/TrainUi.kt").readText()
        assertTrue("the train card's Hide X is 48 dp", ".size(48.dp).clip(CircleShape)" in trains && "onClickLabel = \"Hide for today\"" in trains)
        assertTrue("a stop row keeps a 44 dp target at least", "heightIn(min = 44.dp)" in trains)
        // The card is one button with one spoken sentence; its pieces are not read out again.
        assertTrue("contentDescription = m.spoken" in trains && "clearAndSetSemantics" in trains)
        assertTrue("the trip choice rows are buttons of at least 48 dp", Regex("heightIn\\(min = 64\\.dp\\)[\\s\\S]{0,200}Role\\.Button").containsMatchIn(File(presentation, "trains/ManualTrainUi.kt").readText()))
    }

    @Test
    fun theTrainKeyFieldHasALabel_andItsShowHideToggleADescriptionAndA48dpTarget() {
        val key = File(presentation, "trains/TrainKeySection.kt").readText()
        // The field has a real label (read by a screen reader), not only a placeholder.
        assertTrue("label = { Text(\"Train data key\") }" in key)
        // Show/Hide says what it does and for what, and is an AppButton, whose compact size still keeps a 48 dp target.
        assertTrue("contentDescription = if (shown) \"Hide train data key\" else \"Show train data key\"" in key)
        assertTrue("AppButton(" in key.substringAfter("trailingIcon"))
        assertTrue("heightIn(min = 48.dp)" in File(presentation, "common/Buttons.kt").readText())
        // Error text is shown under the field in words, and the last action of the keyboard is Done.
        assertTrue("isError = problem != null" in key && "ImeAction.Done" in key)
        assertEquals(0, Regex("contentDescription = null").findAll(key).count())
    }

    @Test
    fun pagesAndSectionsAreHeadingsAndEscapeGoesBack() {
        val pages = File(presentation, "common/PageHost.kt").readText()
        assertTrue("Modifier.heading()" in pages && "paneTitle" in pages)
        assertTrue("Key.Escape" in pages && "Key.Escape" in File(presentation, "common/SheetHost.kt").readText())
        assertTrue("heading()" in File(presentation, "dashboard/components/DashboardSections.kt").readText())
    }

    @Test
    fun theCardFaceIsOneSpokenSentence_andItsLogosAreDecoration() {
        val face = File(presentation, "common/CardFace.kt").readText()
        // The bank tile and the network mark sit inside a face that clears its pieces and speaks the card's own sentence.
        assertTrue("clearAndSetSemantics {" in face && "contentDescription = description" in face && "card.spokenDescription" in face)
        assertTrue("the bank tile is decorative here (description = null)", "description = null, plate = true" in face)
        assertEquals("one unlabelled network mark, no other", 1, Regex("contentDescription = null").findAll(face).count())
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
