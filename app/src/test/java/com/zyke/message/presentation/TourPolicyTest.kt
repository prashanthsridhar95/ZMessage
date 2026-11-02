package com.zyke.message.presentation

import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourPage
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.domain.tour.Tours
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the guided tour honest as the app changes. Anchors are how the tour points at real elements; if one is removed
 * from a screen, or two screens claim the same one, the tour would point at nothing, so these scans fail the build.
 */
class TourPolicyTest {
    private fun root(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message") }.first { it.isDirectory }

    private fun presentation(): List<Pair<String, String>> =
        File(root(), "presentation").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root()).invariantSeparatorsPath to it.readText() }.toList()

    private fun code(text: String) = text.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }

    @Test fun everyAnchorIsAttachedToExactlyOneElement() {
        val files = presentation()
        val problems = TourAnchor.entries.mapNotNull { anchor ->
            val uses = files.sumOf { (_, text) -> code(text).count { Regex("""\bTourAnchor\.${anchor.name}\b""").containsMatchIn(it) } }
            if (uses != 1) "${anchor.name} is used $uses times (must be exactly once)" else null
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun anchorsAreRegisteredThroughTheModifier() {
        val used = presentation().filter { !it.first.startsWith("presentation/tour/") }.sumOf { (_, text) -> Regex("""\.tourAnchor\(""").findAll(text).count() }
        assertTrue("no Modifier.tourAnchor( call sites found", used >= 20)
    }

    @Test fun everyStepPointsAtAnAnchorThatExists() {
        val used = presentation().joinToString("\n") { it.second }
        Tours.full().forEach { step ->
            listOfNotNull(step.anchor, step.fallbackAnchor).forEach {
                assertTrue("${step.id}: ${it.name} is not attached to any element", "TourAnchor.${it.name}" in used)
            }
        }
    }

    @Test fun listsThatHoldTourTargetsAreScrollableByTheTour() {
        val text = presentation().joinToString("\n") { it.second }
        listOf(TourTab.HOME, TourTab.ACCOUNTS, TourTab.INSIGHTS, TourTab.BILLS).forEach {
            assertTrue("TourScroll(TourTab.${it.name}, ...) missing", "TourScroll(TourTab.${it.name}," in text)
        }
    }

    @Test fun settingsPagesMapToRealSettingsGroups() {
        val screen = File(root(), "presentation/settings/SettingsScreen.kt").readText()
        val enumBody = screen.substringAfter("private enum class SettingsGroup").substringBefore("\n}")
        val groups = Regex("""^\s*([A-Z_]+)\(""", RegexOption.MULTILINE).findAll(enumBody).map { it.groupValues[1] }.toSet()
        TourPage.entries.mapNotNull { it.settingsGroup }.forEach { assertTrue("Settings has no group $it", it in groups) }
    }

    @Test fun theOverlayUsesOnlyThemeColoursAndTheMoneyAwareText() {
        val forbidden = Regex("""Color\.(Black|White)\b|\bColor\(\s*0x""")
        presentation().filter { it.first.startsWith("presentation/tour/") }.forEach { (name, text) ->
            code(text).forEachIndexed { i, line ->
                assertTrue("$name:${i + 1} uses a colour literal", !forbidden.containsMatchIn(line))
                assertTrue("$name:${i + 1} uses Material's Text", line.trim() != "import androidx.compose.material3.Text")
                assertTrue("$name:${i + 1} uses a bare AppBottomSheet", !Regex("""\bAppBottomSheet\(""").containsMatchIn(line))
            }
        }
    }

    @Test fun theScrimComesFromTheTheme() {
        val host = File(root(), "presentation/tour/TourHost.kt").readText()
        assertTrue(host.contains("colorScheme.scrim"))
    }

    @Test fun theOverlayIsAccessible() {
        val host = File(root(), "presentation/tour/TourHost.kt").readText()
        listOf("paneTitle", "liveRegion", "clearAndSetSemantics", "contentDescription", "rememberReduceMotion", "isTouchExplorationEnabled", "BackHandler").forEach {
            assertTrue("TourHost should use $it", it in host)
        }
    }

    @Test fun tourStateIsLeftOutOfBackups() {
        val prefs = File(root(), "data/backup/PrefsBackup.kt").readText()
        assertTrue(prefs.substringAfter("EXCLUDED = setOf(").substringBefore(")").contains("\"tour_state\""))
    }

    @Test fun theTourIsMountedInTheAppShellAboveSheetsAndPages() {
        val shell = File(root(), "presentation/AppRoot.kt").readText()
        val tour = shell.indexOf("TourHost(")
        assertTrue(tour > 0)
        assertTrue("TourHost must be drawn after the pages and sheets", tour > shell.indexOf("SheetHostLayer(sheetHost)"))
        assertTrue("TourHost must be drawn before the snackbar", tour < shell.indexOf("PrivateSnackbarHost("))
    }
}
