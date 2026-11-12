package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the app's motion calm and in one place (docs/DESIGN.md): nothing loops, every animation spec comes from the
 * Motion tokens, and the reduce-motion / lite switch sits in the one function everything goes through. It scans the
 * motion package and the shared components, which every screen is built from; screens are brought under the same rule
 * wave by wave.
 */
class MotionPolicyTest {

    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }

    private fun presentationRoot() = File(appRoot(), "src/main/java/com/zyke/message/presentation")

    private fun code(file: File): List<String> =
        file.readLines().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

    private fun motionFiles(): List<File> =
        File(presentationRoot(), "motion").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The shared components every screen is made of, plus the shell that moves tabs, pages and sheets, and Home (the reference screen). */
    private val shared = listOf(
        "common/FintechCard.kt", "common/PageHost.kt", "common/SheetHost.kt", "common/AdaptiveSheet.kt", "common/AppSnackbar.kt",
        "common/Choices.kt", "common/Responsive.kt", "common/PillSwitch.kt", "common/EmptyState.kt", "common/CardHeader.kt",
        "common/Forms.kt", "common/ErrorState.kt", "AppRoot.kt", "home/HomeScreen.kt", "home/BudgetViews.kt",
        "messages/components/Skeleton.kt", "messages/components/AttentionStrip.kt", "theme/Tokens.kt",
        // Messages (wave B1): the list, the row anatomy, the swipe, the bars and the detail sheet.
        "messages/MessagesScreen.kt", "messages/components/MessageList.kt", "messages/components/MessageItem.kt",
        "messages/components/MessageCommon.kt", "messages/components/SwipeActions.kt", "messages/components/TabIndicator.kt",
        "messages/components/InboxModeBar.kt", "messages/components/MessagesFilters.kt", "messages/components/MessagesBars.kt",
        "messages/components/MessageDetailSheet.kt", "messages/components/MergeUi.kt", "messages/components/MiscCards.kt",
        "messages/components/OtpCard.kt", "messages/components/TransactionCards.kt", "messages/components/LifeCard.kt",
        "messages/components/FastTagCard.kt", "messages/components/ScheduledPaymentCard.kt", "messages/components/SendersList.kt",
        "messages/components/ThreadSheet.kt", "common/UnderlineTabs.kt"
    )

    /** The Insights tab (every file under dashboard/) and the year in review, brought under the same rules in wave B3. */
    private val insights: List<String> by lazy {
        val root = presentationRoot()
        File(root, "dashboard").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList() + "review/YearPage.kt"
    }

    /** The Bills tab and the pages beside it (reminders, goals, calendar, budget calendar), brought under the same rules in wave B4. */
    private val bills = listOf(
        "home/BillsPage.kt", "home/BillsKit.kt", "home/PlanItemActions.kt", "home/PlanUi.kt", "home/PlanPages.kt",
        "home/GoalsAndReminders.kt", "home/PaidBillsPage.kt", "home/CalendarUi.kt", "home/GroupUi.kt", "motion/SlideSwap.kt"
    )

    /** Files that may call tween/spring/snap themselves, and why. */
    private val rawSpecsAllowed = mapOf(
        "motion/Motion.kt" to "the funnel: the only place a spec is built, and it returns instant ones when reduced",
        "common/Motion.kt" to "movingBorder: the light round the priority card, a loop that is a faint static outline when reduced (LowEndPolicyTest)"
    )

    private val loop = Regex("""rememberInfiniteTransition|infiniteRepeatable|InfiniteRepeatableSpec|repeatMode|RepeatMode|rememberInfinite""")
    private val rawSpec = Regex("""(?<![\w.])(tween|spring|snap|keyframes)\s*[(<]""")
    private val animationApi = Regex("""\banimate\w*AsState\b|\bAnimatable\(|\bAnimatedVisibility\(|\bAnimatedContent\(|\bupdateTransition\(|\banimateItem\(""")

    @Test
    fun theMotionPackageNeverLoops() {
        val offenders = motionFiles().flatMap { f ->
            code(f).mapIndexedNotNull { i, line ->
                if (!line.trimStart().startsWith("import ") && loop.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Motion is one-shot; no looping animation in the motion package:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun sharedComponentsDoNotLoopEither() {
        val root = presentationRoot()
        val offenders = (shared + insights + bills).filter { it !in rawSpecsAllowed }.flatMap { rel ->
            code(File(root, rel)).mapIndexedNotNull { i, line ->
                if (!line.trimStart().startsWith("import ") && loop.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Shared components must not loop:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun animationSpecsComeFromTheMotionTokens() {
        val root = presentationRoot()
        val files = motionFiles().map { "motion/" + it.name to it } + (shared + insights + bills).filter { !it.startsWith("motion/") }.map { it to File(root, it) }
        val offenders = files.filter { (rel, _) -> rel !in rawSpecsAllowed }.flatMap { (rel, file) ->
            code(file).mapIndexedNotNull { i, line ->
                if (!line.trimStart().startsWith("import ") && rawSpec.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(
            "Use rememberMotion().tween / snappy / gentle / exit (tokens in motion/Motion.kt) instead of a raw spec, or add the file to the allow-list with a reason:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun insightsSwapContentThroughTheMotionFunnel() {
        val root = presentationRoot()
        val rawSwap = Regex("""(?<![\w.])(Crossfade|AnimatedContent)\s*\(""")
        val offenders = (insights + bills.filter { !it.startsWith("motion/") }).flatMap { rel ->
            code(File(root, rel)).mapIndexedNotNull { i, line ->
                if (!line.trimStart().startsWith("import ") && rawSwap.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Use CrossfadeContent / CrossfadeKeyed (motion package) instead of a raw Crossfade or AnimatedContent:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun insightsCardsAreTonalCardsWithOneHeader() {
        val root = presentationRoot()
        val offenders = insights.filter { it != "dashboard/components/DashboardSections.kt" }.flatMap { rel ->
            code(File(root, rel)).mapIndexedNotNull { i, line ->
                if (Regex("""(?<![\w.])SectionCard\s*\(""").containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Insights cards are InsightCard (FintechCard + CardHeader), not the settings SectionCard:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        val kit = File(root, "dashboard/components/InsightKit.kt").readText()
        assertTrue("InsightCard must start with a CardHeader", "CardHeader(title" in kit && "FintechCard(" in kit)
    }

    @Test
    fun insightsChartsAreSizedFromTheirWidthNotAFixedHeight() {
        val root = presentationRoot()
        val fixed = Regex("""\.height\(\s*(\d{3})\.dp\s*\)""")
        val offenders = insights.flatMap { rel ->
            code(File(root, rel)).mapIndexedNotNull { i, line ->
                // A date picker is a dialog's body, not a chart.
                if (fixed.containsMatchIn(line) && "DateRangePicker" !in line) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Size a chart with ChartBox (aspect of its width), not a fixed height of 100 dp or more:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun everyInsightsChartHasATextAlternativeThatHonoursHiddenAmounts() {
        val root = presentationRoot()
        val offenders = insights.filter { rel ->
            val text = code(File(root, rel)).joinToString("\n")
            ("Canvas(" in text || "drawWithCache" in text) && "contentDescription" !in text
        }
        assertTrue("A chart needs a contentDescription (a spoken summary): $offenders", offenders.isEmpty())
        val unmasked = insights.filter { rel ->
            val text = code(File(root, rel)).joinToString("\n")
            Regex("""contentDescription\s*=[^\n]*rupees\(""").containsMatchIn(text) &&
                "privateSpeech" !in text && "maskSpoken" !in text && "hidden" !in text
        }
        assertTrue("Spoken amounts must be masked while hidden (privateSpeech / AmountMasker.maskSpoken): $unmasked", unmasked.isEmpty())
    }

    @Test
    fun theMotionFunnelHonoursReducedMotionAndLite() {
        val funnel = File(presentationRoot(), "motion/Motion.kt").readText()
        assertTrue("rememberMotion must read rememberReduceMotion", Regex("""fun rememberMotion[\s\S]*?rememberReduceMotion\(\)""").containsMatchIn(funnel))
        assertTrue("every spec must be instant when reduced", Regex("""if \(reduced\) snap\(\)""").findAll(funnel).count() >= 3)
        assertTrue("transitions must be none when reduced", "EnterTransition.None" in funnel && "ExitTransition.None" in funnel)
    }

    @Test
    fun everyAnimatingFileInTheMotionPackageIsGatedByTheFunnel() {
        val offenders = motionFiles().filter { f ->
            f.name != "Motion.kt" && code(f).any { animationApi.containsMatchIn(it) || it.contains("animateTo(") } &&
                code(f).none { it.contains("rememberMotion()") || it.contains("rememberReduceMotion()") }
        }.map { it.name }
        assertTrue("These animate without asking rememberMotion(): $offenders", offenders.isEmpty())
    }

    @Test
    fun hapticsFollowTheSettingAndStayInOnePlace() {
        val haptics = File(presentationRoot(), "motion/Haptics.kt").readText()
        assertTrue("Haptics must check the setting before every call", Regex("""if \(enabled\)""").findAll(haptics).count() >= 2)
        val offenders = presentationRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "Haptics.kt" && code(it).any { l -> l.contains("performHapticFeedback(") } }.map { it.name }.toList()
        assertTrue("Call rememberHaptics() instead of performHapticFeedback directly (it respects the setting): $offenders", offenders.isEmpty())
    }

    @Test
    fun theEntranceIsBoundedAndPlaysOnce() {
        val text = File(presentationRoot(), "motion/EnterOnce.kt").readText()
        assertTrue("the entrance must consult the gate", "gate.shouldAnimate(index)" in text)
        assertTrue("the gate must survive rotation", "rememberSaveable" in text)
        val math = File(appRoot(), "../domain/src/main/kotlin/com/zyke/message/domain/motion/MotionMath.kt")
        assertTrue("the stagger is capped", "STAGGER_CAP = 8" in math.readText())
    }

    /**
     * Messages (docs/DESIGN.md, Messages): rows dip on press and share one anatomy, the list animates through the tokens,
     * the swipe ticks at its threshold, comes back at once when motion is reduced, and a screen reader gets its actions.
     */
    @Test
    fun messageRowsAndSwipesFollowTheMessagesRules() {
        val messages = File(presentationRoot(), "messages/components")
        val row = File(messages, "MessageCommon.kt").readText()
        assertTrue("every row dips on press", "pressFeedback(source)" in row && "val RowTile = 48.dp" in row)
        assertTrue("a row carries the swipe's screen-reader actions", "LocalRowCustomActions" in row && "customActions" in row)
        val swipe = File(messages, "SwipeActions.kt").readText()
        assertTrue("the swipe ticks at its threshold", "haptics.tick()" in swipe && "snapshotFlow" in swipe)
        assertTrue("the swipe returns at once when motion is reduced", "motion.reduced" in swipe && "snapTo(SwipeToDismissBoxValue.Settled)" in swipe)
        assertTrue("the swipe offers the actions to TalkBack", "CustomAccessibilityAction" in swipe)
        assertTrue("the swipe takes its colours from the theme", "primaryContainer" in swipe && "secondaryContainer" in swipe)
        val list = File(messages, "MessageList.kt").readText()
        assertTrue("rows move with the item motion", "itemMotion()" in list && "animateItem(" !in list)
        assertTrue("the first rows enter once", "enterOnce(gate" in list)
        assertTrue("no spring is left on the row", "animateContentSize" !in File(messages, "MessageItem.kt").readText())
        val screen = File(presentationRoot(), "messages/MessagesScreen.kt").readText()
        assertTrue("loading, list and empty crossfade", "CrossfadeContent(body" in screen && "rememberEnterGate()" in screen)
        assertTrue("the Messages tabs slide their line with the tab token", "Motion.TAB_MS" in File(messages, "TabIndicator.kt").readText())
        val detail = File(messages, "MessageDetailSheet.kt").readText()
        assertTrue("deleting an entry asks first and gives the firmer buzz", "haptics.reject()" in detail && "AlertDialog(" in detail)
    }

    /**
     * Bills and Calendar (docs/DESIGN.md, Bills and Calendar): one hero, rows on the shared anatomy with equal-width actions,
     * the mark-paid beat and its Paid group, a collapsible chevron that turns once, the calendar's direction-aware paging, day
     * cells that dip and speak, and destructive deletes that ask first and buzz.
     */
    @Test
    fun billsAndCalendarFollowTheBillsRules() {
        val home = File(presentationRoot(), "home")
        val page = File(home, "BillsPage.kt").readText()
        assertTrue("the hero is first and only", "BillsHero(" in page && page.indexOf("BillsHero(") < page.indexOf("\"reminders\""))
        assertTrue("the first cards enter once", "enterOnce(gate" in page && "itemMotion()" in page)
        assertTrue("rows that come and go use the column animation", "AnimatedColumnItems(open" in page && "AnimatedColumnItems(paidLines" in page)
        assertTrue("a paid bill gets a beat then moves", "settling" in page && "haptics.tick()" in page && "markPaid(item)" in page)
        assertTrue("one empty voice", "No bills due" in page && "EmptyState(" in page)
        val kit = File(home, "BillsKit.kt").readText()
        assertTrue("the hero is the tonal hero with a count-up and a revealed bar", "Tonal.Hero" in kit && "AnimatedAmount(" in kit && "ProgressReveal(" in kit)
        assertTrue("the status chip crossfades and the check scales in once", "CrossfadeKeyed(" in kit && "scaleIn(" in kit)
        assertTrue("the chevron turns with a token spec", "rotationZ" in kit && "Motion.QUICK_MS" in kit)
        assertTrue("a row dips on press and keeps its role", "pressFeedback(source)" in kit && "Role.Button" in kit)
        assertTrue("a delete asks first and gives the firmer buzz", "haptics.reject()" in kit && "AlertDialog(" in kit)
        assertTrue("a bill row is the shared PlanRow with a logo tile and a fitted amount", "PlanRow(" in kit && "MerchantChip(" in kit && "RowAmount(" in kit)
        val actions = File(home, "PlanItemActions.kt").readText()
        assertTrue("actions are equal-width cells that stack when narrow", "ResponsiveRow(" in actions && ".cell()" in actions && "FlowRow(" !in actions)
        val group = File(home, "GroupUi.kt").readText()
        assertTrue("a group opens through AppearVisibility and its chevron turns", "AppearVisibility(expanded)" in group && "ChevronTurn(expanded)" in group && "AnimatedVisibility(" !in group)
        val calendar = File(home, "CalendarUi.kt").readText()
        assertTrue("the month grid slides in the direction of time", "SlideSwap(" in calendar && "CalendarPaging.direction" in calendar)
        assertTrue("the title crossfades by month", "CrossfadeKeyed(month" in calendar)
        assertTrue("paging ticks", "haptics.tick()" in calendar)
        assertTrue("a swipe turns the page past a threshold, horizontally only", "detectHorizontalDragGestures" in calendar && "CalendarPaging.swipe(" in calendar)
        assertTrue("a day cell dips, fades its fill and speaks", "pressFeedback(source)" in calendar && "animateColorAsState" in calendar && "CalendarPaging.spoken(" in calendar && "Role.Button" in calendar)
        assertTrue("the dots are capped", "CalendarPaging.dots(" in calendar)
        assertTrue("an empty day is the shared empty state", "EmptyState(\"A free day\"" in calendar)
        val dialogs = File(home, "GoalsAndReminders.kt").readText() + calendar
        assertTrue("goals, reminders and events confirm before deleting", Regex("ConfirmDelete\\(").findAll(dialogs).count() >= 3)
        val slide = File(presentationRoot(), "motion/SlideSwap.kt").readText()
        assertTrue("the slide asks the funnel and is instant when reduced", "rememberMotion()" in slide && "motion.reduced" in slide)
    }

    @Test
    fun billsAndCalendarUseTokensNotOneOffSpacing() {
        val root = presentationRoot()
        val oneOff = Regex("""(?:padding|spacedBy|RoundedCornerShape|width|size)\((?:[a-z]+ = )?(?:10|13|14|18|22)\.dp""")
        val offenders = listOf("home/BillsPage.kt", "home/BillsKit.kt", "home/PlanUi.kt", "home/PlanPages.kt", "home/CalendarUi.kt").flatMap { rel ->
            code(File(root, rel)).mapIndexedNotNull { i, line ->
                if (oneOff.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Use Spacing / Radius tokens instead of 10, 13, 14, 18 or 22 dp one-offs:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
