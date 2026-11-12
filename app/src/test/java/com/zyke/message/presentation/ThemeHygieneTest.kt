package com.zyke.message.presentation

import androidx.compose.ui.graphics.Color
import com.zyke.message.presentation.theme.SemanticColors
import com.zyke.message.presentation.theme.contrastRatio
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the "dark mode text is black" class of bug: colours chosen for one theme only. */
class ThemeHygieneTest {

    /** Files that may use literal colours, and why. Everything else must use MaterialTheme / AppColors tokens. */
    private val allowed = mapOf(
        "review/YearCardImage.kt" to "shareable PNG drawn with its own fixed palette, independent of the theme",
        "common/CardFace.kt" to "a card is drawn white-on-gradient in both themes: white ink, a gold chip and a dark scrim behind the footer",
        "common/SheetHost.kt" to "Color.Black scrim behind a sheet, a dimmer in both modes",
        "home/JourneyUi.kt" to "illustrations: lit windows and aircraft dots are drawn art",
        "messages/components/MessageCommon.kt" to "white tile behind brand logos; white letter on deep monogram fills",
        "dashboard/components/ChartColors.kt" to "category hues, used only as fills/dots, never text",
        "dashboard/components/PlanCard.kt" to "category fills, never text",
        "home/CalendarUi.kt" to "calendar kind dots, never text",
        "theme/" to "the theme itself"
    )

    private val forbidden = Regex("""Color\.(Black|White)\b|\bColor\(\s*0x""")

    private fun sourceRoot(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message/presentation") }.first { it.isDirectory }

    @Test
    fun noLiteralColoursOutsideTheAllowList() {
        val root = sourceRoot()
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (allowed.keys.any { rel.startsWith(it) }) emptySequence()
            else f.readLines().mapIndexedNotNull { i, line ->
                if (!line.trimStart().startsWith("//") && forbidden.containsMatchIn(line)) "$rel:${i + 1}: ${line.trim()}" else null
            }.asSequence()
        }.toList()
        assertTrue(
            "Literal colours found; use MaterialTheme.colorScheme or AppColors (or add the file to the allow-list with a reason):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun semanticColoursReadAsTextOnTheirSurface() {
        val lightSurface = Color(0xFFFFFBFE)
        val darkSurface = Color(0xFF1C1B1F)
        listOf("success" to SemanticColors.light.success, "warning" to SemanticColors.light.warning,
            "danger" to SemanticColors.light.danger, "info" to SemanticColors.light.info).forEach { (n, c) ->
            assertTrue("light $n ${contrastRatio(c, lightSurface)}", contrastRatio(c, lightSurface) >= 4.5f)
        }
        listOf("success" to SemanticColors.dark.success, "warning" to SemanticColors.dark.warning,
            "danger" to SemanticColors.dark.danger, "info" to SemanticColors.dark.info).forEach { (n, c) ->
            assertTrue("dark $n ${contrastRatio(c, darkSurface)}", contrastRatio(c, darkSurface) >= 4.5f)
        }
    }

    @Test
    fun everyDayColourResourceHasANightValue() {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val name = Regex("""<color name="([^"]+)">""")
        val day = name.findAll(File(res, "values/colors.xml").readText()).map { it.groupValues[1] }.toSet()
        val night = name.findAll(File(res, "values-night/colors.xml").readText()).map { it.groupValues[1] }.toSet()
        // Palette entries that are fixed on purpose (brand accent, plain black/white, legacy names).
        val fixed = setOf("purple_200", "purple_500", "purple_700", "teal_200", "teal_700", "black", "white", "zyke_accent")
        val missing = day.filter { it !in night && it !in fixed }
        assertTrue("colours without a values-night value: $missing", missing.isEmpty())
    }
}
