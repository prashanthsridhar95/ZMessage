package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Big content = page, small = sheet": a bare [AppBottomSheet] has no way to become a page, so it is only for the
 * compact pickers and confirmations listed here. Anything else goes through AdaptiveSheet / FormSheet, which turn a
 * sheet that would not fit into an AppPage.
 */
class SheetPolicyTest {

    private val compact = mapOf(
        "common/AppBottomSheet.kt" to "the primitive itself",
        "common/AdaptiveSheet.kt" to "the helper that wraps it",
        "common/FilterSheet.kt" to "a partial-height list of choices",
        "common/PicturePicker.kt" to "two-way choice",
        "common/LinkSheet.kt" to "the in-app web view, which has its own full-height behaviour",
        "home/HomeScreen.kt" to "BudgetSheet: one bar and a few lines"
    )

    private fun root(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message/presentation") }.first { it.isDirectory }

    @Test
    fun bareSheetsAreOnlyUsedForCompactContent() {
        val root = root()
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel in compact) emptySequence()
            else f.readLines().mapIndexedNotNull { i, line ->
                if (Regex("""\bAppBottomSheet\(""").containsMatchIn(line)) "$rel:${i + 1}" else null
            }.asSequence()
        }.toList()
        assertTrue(
            "Use AdaptiveSheet (big content becomes a page) or add the file to the compact list with a reason:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun sheetsAreNotGivenAFixedHeight() {
        val root = root()
        val fixed = Regex("""SHEET_HEIGHT|AppBottomSheet[^\n]*\.height\(\s*\d+\.dp""")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { fixed.containsMatchIn(it) } }.map { it.name }.toList()
        assertTrue("Fixed sheet heights break small phones and large fonts: $offenders", offenders.isEmpty())
    }
}
