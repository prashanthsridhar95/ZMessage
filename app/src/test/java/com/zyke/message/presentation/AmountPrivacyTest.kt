package com.zyke.message.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.zyke.message.presentation.common.TimedAmountReveal
import com.zyke.message.presentation.common.maskAnnotated
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hidden amounts: the reveal window, the styled-text masker, and a source scan that keeps new screens from leaking figures. */
@OptIn(ExperimentalCoroutinesApi::class)
class AmountPrivacyTest {

    private class Rig(authRequired: Boolean = true, lock: Boolean = true, val passes: Boolean = true) {
        val scope = TestScope(UnconfinedTestDispatcher())
        var asked = 0
        val reveal = TimedAmountReveal(
            scope = scope, clock = { scope.currentTime },
            authRequired = { authRequired }, screenLockExists = { lock },
            authenticate = { cb -> asked++; cb(passes) }
        )
    }

    @Test fun staysHiddenWhenTheLockIsFailed() {
        val rig = Rig(passes = false)
        var shown = false
        rig.reveal.requestReveal { shown = true }
        assertEquals(1, rig.asked)
        assertFalse(rig.reveal.revealed.value)
        assertFalse(shown)
    }

    @Test fun authenticatedWindowLastsAMinuteThenHides() {
        val rig = Rig()
        rig.reveal.requestReveal()
        assertTrue(rig.reveal.revealed.value)
        rig.scope.advanceTimeBy(59_000)
        assertTrue(rig.reveal.revealed.value)
        rig.scope.advanceTimeBy(1_001)
        assertFalse(rig.reveal.revealed.value)
        assertEquals(0L, rig.reveal.revealedUntil.value)
    }

    @Test fun noScreenLockFallsBackToAFiveSecondTap() {
        val rig = Rig(lock = false)
        rig.reveal.requestReveal()
        assertEquals(0, rig.asked)
        assertTrue(rig.reveal.revealed.value)
        rig.scope.advanceTimeBy(5_001)
        assertFalse(rig.reveal.revealed.value)
    }

    @Test fun settingOffSkipsTheLockButKeepsTheShortWindow() {
        val rig = Rig(authRequired = false)
        rig.reveal.requestReveal()
        assertEquals(0, rig.asked)
        assertFalse(rig.reveal.asksForAuthentication)
        assertTrue(rig.reveal.revealed.value)
    }

    @Test fun hideNowHidesAtOnce() {
        val rig = Rig()
        rig.reveal.requestReveal()
        rig.reveal.hideNow()
        assertFalse(rig.reveal.revealed.value)
        rig.scope.advanceTimeBy(120_000) // the old expiry must not fire a second time
        assertFalse(rig.reveal.revealed.value)
    }

    @Test fun maskedStyledTextKeepsItsStylesOnTheRightWords() {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        // "Paid ₹1,200 to Ravi": bold on "₹1,200" (5..11) and on "Ravi" (15..19)
        val text = AnnotatedString("Paid ₹1,200 to Ravi", listOf(AnnotatedString.Range(bold, 5, 11), AnnotatedString.Range(bold, 15, 19)))
        val masked = maskAnnotated(text)
        assertEquals("Paid ₹ •••• to Ravi", masked.text)
        val spans = masked.spanStyles
        assertEquals(5, spans[0].start); assertEquals(11, spans[0].end)
        assertEquals("Ravi", masked.text.substring(spans[1].start, spans[1].end))
    }

    @Test fun styledTextWithoutAmountsIsReturnedAsIs() {
        val text = AnnotatedString("12 items, 45% used")
        assertSame(text, maskAnnotated(text))
    }

    // ---- source scan

    private fun root(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message") }.first { it.isDirectory }

    private fun kotlinFiles(sub: String): List<Pair<String, File>> {
        val base = File(root(), sub)
        return base.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root()).invariantSeparatorsPath to it }.toList()
    }

    /** Public market quotes are not the person's money, so their screens keep Material's plain Text. */
    private val plainTextAllowed = setOf("presentation/common/MoneyAwareText.kt", "presentation/markets/MarketUi.kt", "presentation/markets/MarketSettings.kt")

    @Test fun screensUseTheMoneyAwareText() {
        val offenders = kotlinFiles("presentation").filter { (rel, f) ->
            rel !in plainTextAllowed && f.readLines().any { line ->
                val t = line.trim()
                !t.startsWith("//") && !t.startsWith("*") &&
                    (t == "import androidx.compose.material3.Text" || t.contains("androidx.compose.material3.Text(") || t == "import androidx.compose.material3.*")
            }
        }.map { it.first }
        assertTrue(
            "These use Material's Text, which would show amounts while they are hidden. Import com.zyke.message.presentation.common.Text instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test fun spokenTextWithAmountsGoesThroughTheMasker() {
        val guard = listOf("privateSpeech", "maskSpoken", "LocalPrivacyMode", "AmountMasker")
        val offenders = kotlinFiles("presentation").filter { (_, f) ->
            val lines = f.readLines()
            val risky = lines.indices.any { i ->
                val l = lines[i]
                (l.contains("contentDescription =") || l.contains("stateDescription =")) &&
                    (i..minOf(i + 3, lines.lastIndex)).any { j -> lines[j].contains("DisplayFormatters.rupees") }
            }
            risky && guard.none { g -> lines.any { it.contains(g) } }
        }.map { it.first }
        assertTrue("Screen-reader text with an amount must be masked while hidden (privateSpeech / AmountMasker.maskSpoken):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun notificationsAndWidgetsMaskAmounts() {
        val offenders = kotlinFiles("notifications").filter { (rel, f) ->
            rel != "notifications/NotificationPrivacy.kt" && f.readText().contains("DisplayFormatters.rupees") &&
                !f.readText().contains("NotificationPrivacy")
        }.map { it.first }
        assertTrue("Notification or widget text with an amount must go through NotificationPrivacy:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun toastsNeverCarryRawAmounts() {
        val offenders = kotlinFiles("presentation").flatMap { (rel, f) ->
            f.readLines().mapIndexedNotNull { i, l ->
                if (l.contains("Toast.makeText") && l.contains("rupees(")) "$rel:${i + 1}" else null
            }
        }
        assertTrue("A toast with an amount must be masked while hidden:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
