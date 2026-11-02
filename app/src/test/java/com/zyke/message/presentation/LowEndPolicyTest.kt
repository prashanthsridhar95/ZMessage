package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards what keeps the app usable on a budget phone (Redmi, POCO, Galaxy A0x): little memory, a weak GPU, a narrow
 * screen, large text, and a vendor that forces dark mode. Each rule is easy to break by accident and invisible on a
 * Pixel. See docs/LOWEND.md.
 */
class LowEndPolicyTest {

    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }

    private fun presentation(): List<Pair<String, File>> {
        val root = File(appRoot(), "src/main/java/com/zyke/message/presentation")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { it.substringBefore("//") }

    /**
     * Looping animations keep the GPU and the CPU busy for as long as the screen is open. Every one must be switched off
     * by reduced motion, which lite mode implies (rememberReduceMotion reads LocalLite): the call or a line just before
     * it has to branch on that. Files that may loop are listed with why.
     */
    private val loopingAllowed = mapOf(
        "common/Motion.kt" to "movingBorder: the light round the priority card; static outline when reduced",
        "home/JourneyUi.kt" to "the journey scene's clouds and wheels; frozen when reduced",
        "messages/components/Skeleton.kt" to "the loading pulse; a still placeholder when reduced"
    )

    @Test
    fun loopingAnimationsAreSwitchedOffByReducedMotionAndLiteMode() {
        val call = Regex("""rememberInfiniteTransition\(|infiniteRepeatable\(|InfiniteRepeatableSpec""")
        val gate = Regex("""\breduce(Motion)?\b|\bstill\b|\blite\b|LocalLite""")
        val offenders = presentation().flatMap { (rel, file) ->
            val lines = codeLines(file)
            lines.mapIndexedNotNull { i, line ->
                if (!call.containsMatchIn(line) || line.trimStart().startsWith("import ")) return@mapIndexedNotNull null
                when {
                    rel !in loopingAllowed -> "$rel:${i + 1}: not on the allow-list (add it with a reason, gated by rememberReduceMotion)"
                    (maxOf(0, i - 6)..i).none { gate.containsMatchIn(lines[it]) } -> "$rel:${i + 1}: not gated by reduce motion / lite"
                    else -> null
                }
            }
        }
        assertTrue("Looping animations must be gated:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** The market strip's marquee never stops; in lite mode it is swiped by hand instead. */
    @Test
    fun marqueeIsGated() {
        val offenders = presentation().filter { (_, f) -> codeLines(f).any { it.contains("basicMarquee(") && !it.trimStart().startsWith("import ") } }
            .filter { (rel, f) -> rel != "markets/MarketUi.kt" || f.readText().let { "rememberReduceMotion()" !in it || "if (still)" !in it } }
            .map { it.first }
        assertTrue("basicMarquee must only be used in MarketUi, behind rememberReduceMotion:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Blur and render effects are far too heavy for a weak GPU. */
    @Test
    fun noBlurOrRenderEffects() {
        val heavy = Regex("""\.blur\(|\bRenderEffect\b|BlurEffect|Modifier\.shadow\(""")
        // LinkSheet: the minimised web button is one small circle that is dragged around.
        val allowed = mapOf("common/LinkSheet.kt" to "one small draggable circle")
        val offenders = presentation().filter { it.first !in allowed }.flatMap { (rel, f) ->
            codeLines(f).mapIndexedNotNull { i, line ->
                if (heavy.containsMatchIn(line) && !line.trimStart().startsWith("import ")) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Blur, render effects and shadows are not allowed (flat surfaces with a border are):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** An off-screen layer costs a full-screen texture; the tour keeps the only one, and has a flat version for lite mode. */
    @Test
    fun offscreenLayersOnlyInTheTourSpotlightWhichHasALiteVersion() {
        val offenders = presentation().filter { it.first != "tour/TourHost.kt" }.flatMap { (rel, f) ->
            codeLines(f).mapIndexedNotNull { i, line -> if ("CompositingStrategy.Offscreen" in line) "$rel:${i + 1}" else null }
        }
        assertTrue("Offscreen compositing outside the tour:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        val tour = File(appRoot(), "src/main/java/com/zyke/message/presentation/tour/TourHost.kt").readText()
        assertTrue("The tour spotlight needs a lite version without Offscreen", "LocalLite" in tour && "if (lite)" in tour)
    }

    /** Lite mode reaches every screen that honours reduced motion because rememberReduceMotion reads it. */
    @Test
    fun liteImpliesReducedMotion() {
        val text = File(appRoot(), "src/main/java/com/zyke/message/presentation/common/Accessibility.kt").readText()
        assertTrue("rememberReduceMotion must include LocalLite", Regex("""fun rememberReduceMotion[\s\S]*?LocalLite""").containsMatchIn(text))
    }

    /** A fixed width wider than a 320 dp phone overflows it. Use fitWidthMax / widthIn(max = ...) instead. */
    @Test
    fun noFixedWidthWiderThanASmallPhone() {
        val fixed = Regex("""\.(width|requiredWidth|size|requiredSize)\((\d{3,})(\.\d+)?\.dp""")
        val offenders = presentation().flatMap { (rel, f) ->
            codeLines(f).mapIndexedNotNull { i, line ->
                val m = fixed.find(line)
                if (m != null && m.groupValues[2].toInt() > MAX_FIXED_DP) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Fixed sizes over $MAX_FIXED_DP dp overflow a 320 dp phone:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** The five bottom tabs share 320 to 411 dp: their labels shrink to fit and follow text size only up to 1.3. */
    @Test
    fun bottomBarLabelsFitAndTheBarCapsTextSize() {
        val text = File(appRoot(), "src/main/java/com/zyke/message/presentation/AppRoot.kt").readText()
        assertTrue("nav labels must use FitText", "FitText(tab.label" in text)
        assertTrue("the nav bar must sit in ChromeScale", Regex("""ChromeScale\s*\{\s*NavigationBar\(""").containsMatchIn(text))
    }

    /** Xiaomi, OPPO and Samsung can force dark mode on apps that do not opt out, which inverts the app's own colours. */
    @Test
    fun forceDarkIsOptedOut() {
        val theme = File(appRoot(), "src/main/res/values-v29/themes.xml").readText()
        assertTrue("Theme.ZykeMessage must set android:forceDarkAllowed to false", Regex("""name="android:forceDarkAllowed">\s*false""").containsMatchIn(theme))
        assertTrue(Regex("""name="Theme\.ZykeMessage"""").containsMatchIn(theme))
    }

    /** The pretend phone only ever applies in developer mode and is kept out of every backup. */
    @Test
    fun theSimulatorNeedsDeveloperModeAndStaysOutOfBackups() {
        val main = File(appRoot(), "src/main/java/com/zyke/message/presentation/MainActivity.kt").readText()
        assertTrue("the simulator profile must be gated by developer mode", Regex("""if \(developer\) pretend else""").containsMatchIn(main))
        val log = File(appRoot(), "src/main/java/com/zyke/message/data/diag/DevLog.kt").readText()
        assertTrue("the profile is stored in the developer preferences", "PREFS = \"developer\"" in log && "DEVICE" in log)
        val backup = File(appRoot(), "src/main/java/com/zyke/message/data/backup/PrefsBackup.kt").readText()
        assertTrue("developer and perf preferences are not backed up", "\"developer\"" in backup && "\"perf\"" in backup)
    }

    private companion object {
        const val MAX_FIXED_DP = 280
    }
}
