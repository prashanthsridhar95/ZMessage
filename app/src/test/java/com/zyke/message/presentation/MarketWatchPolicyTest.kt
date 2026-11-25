package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Market watch is one Kite-style watchlist, on Home and on its page; these guard its shape. */
class MarketWatchPolicyTest {
    private val ui: String = listOf("src/main/java", "app/src/main/java").map { File(it, "com/zyke/message/presentation/markets/MarketUi.kt") }.first { it.isFile }.readText()

    @Test
    fun homeShowsTheWatchlistItself_notAJustTwoIndexLine() {
        val strip = ui.substringAfter("fun MarketStrip").substringBefore("fun MarketPage")
        assertTrue("the first names are rows on Home", "HOME_ROWS" in ui && "WatchRow(" in strip)
        assertTrue("a row opens its sheet", "StockSheet(" in strip)
        assertTrue("a quiet See all opens the page", "See all" in strip && "onClick = onOpen" in strip)
        assertTrue("the index header opens the Overview", "IndexHeader(" in strip && "IndexOverview(" in strip)
    }

    @Test
    fun rowsAreOneHeight_withTabularFigures_andNoScrollingTicker() {
        assertTrue("56 dp rows", "ROW_HEIGHT = 56.dp" in ui && "heightIn(min = ROW_HEIGHT)" in ui)
        assertTrue("tabular figures", "TABULAR = \"tnum\"" in ui)
        assertFalse("no marquee ticker", "basicMarquee" in ui)
        assertFalse("no two-column index line", "fun IndexLine" in ui)
    }

    @Test
    fun overviewLinesAreColouredByTheYearsMove_andLoadingAndOfflineAreDesigned() {
        assertTrue("year colour", "values.last() >= values.first()" in ui)
        assertTrue("skeleton while loading", "fun WatchSkeleton" in ui)
        assertTrue("offline retry", "Can't reach the price service" in ui && "Try again" in ui)
    }

    @Test
    fun noLabelledReturnsInsideComposableLambdas() {
        val composable = ui.substringAfter("fun MarketStrip")
        assertFalse("D8 rejects a labelled return in a composable lambda", Regex("""return@(?!LaunchedEffect)\w+""").containsMatchIn(composable))
    }
}
