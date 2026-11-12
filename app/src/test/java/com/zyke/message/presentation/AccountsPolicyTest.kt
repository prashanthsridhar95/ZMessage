package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the Accounts tab (bank accounts, cards, investments, and their sheets) on the design system (docs/DESIGN.md,
 * "Accounts"): the shared tokens, motion from the funnel, one-shot movement, haptics where a choice is made or something
 * is thrown away. MotionPolicyTest covers the shared components; this covers the screens of this wave.
 */
class AccountsPolicyTest {

    private fun presentation(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message/presentation") }.first { it.isDirectory }

    private fun text(rel: String): String = File(presentation(), rel).readText()

    private fun code(rel: String): List<String> =
        File(presentation(), rel).readLines().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

    private val screens = listOf(
        "home/AccountsUi.kt", "home/AccountsHeader.kt", "home/CardsTab.kt", "home/FintechUi.kt", "home/BalanceCheckUi.kt",
        "investments/InvestmentsTab.kt", "investments/InvestmentUi.kt", "investments/InvestmentDetail.kt", "investments/InvestmentDialogs.kt",
        "common/HeaderRegion.kt", "common/SpendCard.kt", "common/CardFace.kt", "common/TouchAndReveal.kt"
    )

    private val loop = Regex("""rememberInfiniteTransition|infiniteRepeatable|InfiniteRepeatableSpec|repeatMode|RepeatMode""")
    private val rawSpec = Regex("""(?<![\w.])(tween|spring|snap|keyframes)\s*[(<]""")

    private fun offenders(regex: Regex, files: List<String> = screens): List<String> = files.flatMap { rel ->
        code(rel).mapIndexedNotNull { i, line -> if (!line.trimStart().startsWith("import ") && regex.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null }
    }

    @Test
    fun theAccountsScreensNeverLoopAndTakeEverySpecFromTheMotionTokens() {
        assertTrue("no looping animation:\n" + offenders(loop).joinToString("\n"), offenders(loop).isEmpty())
        assertTrue(
            "use rememberMotion().tween / snappy / gentle instead of a raw spec:\n" + offenders(rawSpec).joinToString("\n"),
            offenders(rawSpec).isEmpty()
        )
    }

    @Test
    fun spacingAndCornersComeFromTheTokens() {
        // CardFace is artwork (a real card's proportions and a drawn chip), so its own few numbers stay.
        val files = screens - "common/CardFace.kt"
        val literal = Regex("""RoundedCornerShape\(\s*\d|spacedBy\(\s*\d+\.dp|contentSpacing = \d+\.dp|padding\(\s*(?:[a-z]+\s*=\s*)?[1-9]\d*\.dp""")
        val found = offenders(literal, files)
        assertTrue("use Spacing / Radius / AppShapes instead of a number:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theHeroFiguresCountThroughAnimatedAmount() {
        assertTrue("no legacy count-up helper on these screens", offenders(Regex("""rememberAnimatedRupees""")).isEmpty())
        val header = text("home/AccountsHeader.kt")
        assertEquals("the bank total and the investments total", 2, Regex("""fromZero = entering""").findAll(header).count())
        assertTrue("each sub-tab's hero counts once, on its first visit", "fun EnterGate.heroEntering" in header && "played = true" in header)
        assertTrue("one gate per sub-tab", "heroGates" in text("home/FintechUi.kt"))
    }

    @Test
    fun theOldRedesignLeftoversAreGone() {
        val all = presentation().walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        listOf("BankAccountTile", "CardStatusPanel", "CardsHero", "InvestmentsHero", "ACCOUNTS_HERO_MIN_HEIGHT", "fun Sparkline").forEach { name ->
            assertFalse("$name is unused and was removed", all.any { name in it })
        }
        assertTrue("Home's hero stays", "fun BalanceHero" in text("home/FintechUi.kt"))
        assertTrue("Settings still draws its deck with CardDeck", "fun CardDeck" in text("common/CardFace.kt"))
    }

    @Test
    fun theDeckMovesThroughTheTokensAndTicksWhenASwipeCommits() {
        val deck = text("common/HeaderRegion.kt")
        assertTrue("the deck asks rememberMotion()", "rememberMotion()" in deck)
        assertTrue("fly-off is a short emphasized slide", "motion.tween<Float>(Motion.STANDARD_MS, Motion.Emphasized)" in deck)
        assertTrue("the spring back is the gentle spring", "motion.gentle<Float>()" in deck)
        assertTrue("the commit rule is the tested one", "DeckModel.commits(" in deck)
        assertTrue("a tick when the card is sent away, none on a spring back", deck.contains("if (away) onCommit()") && deck.contains("haptics.tick()"))
        assertTrue("the dots change width with tokens", "animateDpAsState" in deck && "motion.tween<Dp>(Motion.QUICK_MS)" in deck)
        assertTrue("the front title crossfades with the front card", "CrossfadeContent(frontKey" in text("home/AccountsHeader.kt"))
    }

    @Test
    fun everyTappableCellDipsAndTicks() {
        val deck = text("common/HeaderRegion.kt")
        val cell = deck.substringAfter("private fun ActionCell").substringBefore("Slot colours")
        assertTrue("the action circle dips while held", "pressFeedback(source" in cell)
        assertTrue("and gives a light tick on a tap", "haptics.tick(); action.onClick()" in cell)
        assertTrue("the icon and the working ring swap by crossfade in a fixed circle", "CrossfadeContent(" in cell && "Modifier.size(CIRCLE)" in cell)
        val investments = text("investments/InvestmentsTab.kt")
        assertTrue("chips and tiles use the shared pressable", investments.contains(".tappable(") && !investments.contains(".clickable("))
    }

    @Test
    fun theSubTabsSwitchBySharedAxisAndShowSkeletonsWhileLoading() {
        val page = text("home/FintechUi.kt")
        assertTrue("one header item whose content crossfades", Regex("""item\(key = "header"\)[\s\S]*?CrossfadeContent\(""").containsMatchIn(page))
        assertTrue("the body slides along the shared axis by the tab distance", "Motion.TAB_SLIDE_DP" in page && "Motion.TAB_MS" in page)
        assertTrue("a still skeleton while the first read goes", "HeaderSkeleton()" in page && "BodySkeleton(" in page && "fadeInOnce()" in page)
        assertTrue("the shared empty state, one voice", "They appear when your bank texts you about a card." in page && "EmptyState(" in page)
    }

    @Test
    fun destructiveConfirmsBuzz() {
        assertTrue("Leave out and Remove", Regex("""haptics\.reject\(\)""").findAll(text("home/FintechUi.kt")).count() >= 2)
        assertTrue("Remove limit", "haptics.reject()" in text("home/CardsTab.kt"))
        assertTrue("Delete an investment", "haptics.reject()" in text("investments/InvestmentDetail.kt"))
    }

    @Test
    fun theSpendCardBarsGrowOnceAndItsRowsRiseInTurn() {
        val spend = text("common/SpendCard.kt")
        assertTrue("the limit bar is a ProgressReveal", "ProgressReveal(" in spend)
        assertTrue("category rows enter in turn", "enterOnce(gate, i)" in spend && "rememberEnterGate()" in spend)
        assertTrue("the segmented bar is revealed once", ".revealOnce()" in text("common/HeaderRegion.kt"))
        assertTrue("the section label is the shared CardHeader", "CardHeader(title)" in spend)
    }

    @Test
    fun theBalanceCheckSwapIsOneCrossfadeInAFixedSlot() {
        val row = text("home/BalanceCheckUi.kt").substringAfter("fun CheckBalanceRow").substringBefore("Whether the person has switched on")
        assertTrue("button, progress and updated swap by crossfade", "CrossfadeContent(state, Modifier.heightIn(min = 48.dp)" in row)
    }

    @Test
    fun theInvestmentRowsComeAndGoWithItemMotion() {
        val tab = text("investments/InvestmentsTab.kt")
        assertTrue("holdings, closed rows and the matured cards", Regex("""itemMotion\(\)""").findAll(tab).count() >= 5)
        assertTrue("the value in a row counts when it changes", "AnimatedAmount(" in tab)
        assertTrue("gain colours come from the theme", "AppColors.success" in text("investments/InvestmentUi.kt") && "AppColors.danger" in text("investments/InvestmentUi.kt"))
    }
}
