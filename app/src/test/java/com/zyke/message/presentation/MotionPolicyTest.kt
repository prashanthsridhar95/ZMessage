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
        "messages/components/Skeleton.kt", "messages/components/AttentionStrip.kt", "theme/Tokens.kt"
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
        val offenders = shared.filter { it !in rawSpecsAllowed }.flatMap { rel ->
            code(File(root, rel)).mapIndexedNotNull { i, line ->
                if (!line.trimStart().startsWith("import ") && loop.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Shared components must not loop:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun animationSpecsComeFromTheMotionTokens() {
        val root = presentationRoot()
        val files = motionFiles().map { "motion/" + it.name to it } + shared.map { it to File(root, it) }
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
}
