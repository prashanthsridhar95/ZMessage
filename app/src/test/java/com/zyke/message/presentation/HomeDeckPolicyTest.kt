package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps Home's deck honest (docs/DESIGN.md, "Home deck"): safety of auto-clear, the gestures having an alternative, motion through
 * the tokens, and colours from the theme only.
 */
class HomeDeckPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private val presentation get() = File(appRoot(), "src/main/java/com/zyke/message/presentation")
    private fun text(rel: String) = File(presentation, rel).readText()
    private fun code(rel: String) = text(rel).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }

    private val deckFiles = listOf("home/HomeDeck.kt", "home/TriageScreen.kt", "home/TriageSettingsPage.kt")

    @Test
    fun theDeckUsesNoLiteralColoursAndNoLoopsAndNoRawSpecs() {
        val literal = Regex("""Color\.(Black|White)\b|\bColor\(\s*0x""")
        val loops = Regex("""rememberInfiniteTransition|infiniteRepeatable|RepeatMode""")
        val raw = Regex("""(?<![\w.])(tween|spring|snap|keyframes)\s*[(<]""")
        for (f in deckFiles + "motion/CountUp.kt") {
            code(f).forEachIndexed { i, line ->
                assertFalse("$f:${i + 1} literal colour", literal.containsMatchIn(line))
                assertFalse("$f:${i + 1} loop", loops.containsMatchIn(line))
                assertFalse("$f:${i + 1} raw animation spec", raw.containsMatchIn(line))
            }
        }
        // The only literals for the stacks are in the theme, and they come as light and dark pairs.
        assertEquals(8, Regex("""StackTone\(Color\(0x""").findAll(text("theme/StackPalette.kt")).count() / 2)
    }

    @Test
    fun theSettingsOfferAutoClearOnlyWhereTheDomainAllowsIt() {
        val page = text("home/TriageSettingsPage.kt")
        assertTrue("the choices come from the stack's own allowed modes", "spec.allowed.map" in page)
        assertFalse("no hand-made Auto clear choice", Regex("""Choice\(\s*TriageMode\.AUTO_CLEAR""").containsMatchIn(page))
        // The deck is built by one function that settings go through; a stack that is not auto-clearable is never an auto-cleared one.
        val build = File(appRoot(), "../domain/src/main/kotlin/com/zyke/message/domain/triage/TriageStacks.kt").readText()
        assertTrue("auto-clear needs the stack to allow it", "mode == TriageMode.AUTO_CLEAR && spec.canAutoClear" in build)
        assertTrue("and the message to be an expired code or marketing", "autoClearable(it, now)" in build)
        val settings = File(appRoot(), "../domain/src/main/kotlin/com/zyke/message/domain/triage/TriageSettings.kt").readText()
        assertEquals("only Codes and Offers can auto clear", 2, Regex("""canAutoClear = true""").findAll(settings).count())
    }

    @Test
    fun everyGestureOnTheCardIsAlsoAButtonAndAScreenReaderAction() {
        val screen = text("home/TriageScreen.kt")
        for (label in listOf("Sweep it", "Got it", "Previous card", "Next card")) {
            assertTrue("custom action $label", Regex("""CustomAccessibilityAction\("$label"""").containsMatchIn(screen))
        }
        assertTrue("a control bar with the four buttons", Regex("""BarButton\(""").findAll(screen).count() >= 5)
        assertTrue("buttons are at least 48 dp", "heightIn(min = 52.dp)" in screen)
        assertTrue("the commit tick", "haptics.tick()" in screen)
        assertTrue("undo after every action", Regex("""withUndo\(""").findAll(screen).count() >= 2)
        assertTrue("reduced motion means no tilt", "if (!currentReduce) rotationZ" in screen)
    }

    @Test
    fun theDeckSectionIsOneKeyedItemWithATourAnchor() {
        val home = text("home/HomeScreen.kt")
        assertTrue(Regex("""item\(key = "deck"\)""").containsMatchIn(home))
        assertEquals(1, Regex("""TourAnchor\.HOME_DECK""").findAll(home).count())
        val deck = text("home/HomeDeck.kt")
        assertTrue("tiles enter once, in turn", "enterOnce(gate" in deck)
        assertTrue("the count comes from the motion package", "rememberCountUpInt" in deck)
        assertTrue("a long press is not needed: tiles are tappable buttons", "tappable(" in deck)
    }

    @Test
    fun theCardStackShowsWhereYouAreAndHintsQuietly() {
        val screen = text("home/TriageScreen.kt")
        assertTrue("the same page dots as the Accounts deck", "DeckDots(cards.size" in screen)
        assertFalse("archiving is never drawn in the error colour", "errorContainer" in screen)
        assertTrue("the swipe hint has icons", "baseline_archive_24" in screen && "baseline_check_24" in screen)
        assertTrue("the two decks tilt the same", "TILT_DEGREES = 8f" in screen && "TILT_DEGREES = 8f" in text("common/HeaderRegion.kt"))
        assertFalse("the decision buttons are equal in width", Regex("""weight\(1\.3f\)""").containsMatchIn(screen))
    }

    @Test
    fun homeShowsOneRealStackAndMergesTheReviewIntoIt() {
        val deck = text("home/HomeDeck.kt")
        assertTrue("the same swipe deck as the triage page, inline", "TriageDeck(" in deck && "compact = true" in deck)
        assertTrue("swipes archive and keep with undo and a tick", "Intent.TriageSweep" in deck && "Intent.TriageGotIt" in deck && "haptics.tick()" in deck && Regex("""withUndo\(""").findAll(deck).count() >= 2)
        assertTrue("page dots and the quiet hint", "DeckDots(cards.size" in deck && "Swipe left to archive, right to keep" in deck)
        assertTrue("the review is the deck's count and chip, not a competing row", "to review" in deck && "ReviewCard(" in deck && "DeckChip(" in deck)
        assertEquals("one tour anchor for the review", 1, Regex("""TourAnchor\.HOME_REVIEW""").findAll(deck).count())
        assertFalse("no labelled return in a composable lambda", Regex("""return@""").containsMatchIn(deck))
        val home = text("home/HomeScreen.kt")
        assertTrue("the caught-up row shows whenever the deck is on", "val showDeckSection = layout.shows(HomeSection.DECK) || state.review.count > 0" in home)
    }
}
