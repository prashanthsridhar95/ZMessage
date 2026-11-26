package com.zyke.message.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Market watch: a slim scrolling ticker as Home's first item, and a Kite-style watchlist on its own page.
 * These guard the shape of both.
 */
class MarketWatchPolicyTest {
    private fun file(rel: String) = listOf("src/main/java", "app/src/main/java").map { File(it, rel) }.first { it.isFile }
    private val ui: String = file("com/zyke/message/presentation/markets/MarketUi.kt").readText()
    private val home: String = file("com/zyke/message/presentation/home/HomeScreen.kt").readText()
    private val strip get() = ui.substringAfter("fun MarketStrip").substringBefore("fun MarketPage")
    private val page get() = ui.substringAfter("fun MarketPage")

    @Test
    fun homeShowsOnlyASlimTickerLine_noRowsNoChevronNoOverview() {
        assertTrue("the ticker band is one slim line", "STRIP_HEIGHT = 40.dp" in ui && ".height(STRIP_HEIGHT)" in strip)
        assertTrue("items are NAME, price, arrow and move", "fun TickerItem(" in ui && "▲ " in ui && "▼ " in ui)
        assertTrue("a tap opens the Market watch page", "onClick = onOpen" in strip && "Open market watch" in strip)
        assertFalse("no stock rows on Home", "WatchRow(" in strip || "StockSheet(" in strip)
        for (gone in listOf("ChevronTurn", "IndexOverview", "IndexHeader", "AppearVisibility", "overviewOpen", "OverviewCell", "YearLine", "HOME_ROWS", "See all")) {
            assertFalse("$gone is gone: no expand or collapse anywhere", gone in ui)
        }
    }

    @Test
    fun theTickerScrollsOnlyBehindReduceMotion_andIsStaticOtherwise() {
        assertTrue("the marquee is the system one, drawn once", "basicMarquee(" in strip)
        assertTrue("gated by reduce motion / Lite", "rememberReduceMotion()" in strip && "if (still)" in strip)
        assertTrue("still: only the items that fit whole", "WholeItemsRow(" in strip)
        assertFalse("no frame loop of our own", "withFrameNanos" in ui || "rememberInfiniteTransition" in ui)
    }

    @Test
    fun theStripIsHomesFirstItem_aboveTheBalance_andAnchorsAppearOnce() {
        val summary = home.substringAfter("private fun SummaryPage(")
        val market = summary.indexOf("item(key = \"market\")")
        assertTrue("the market item exists", market > 0)
        for (later in listOf("item(key = \"tour-banner\")", "item(key = \"blank\")", "item(key = \"balance\")", "item(key = \"focus\")")) {
            assertTrue("market comes before $later", market < summary.indexOf(later))
        }
        assertTrue("still behind Customize Home and Market watch being on", "layout.shows(HomeSection.MARKET) && marketWatchOn" in summary)
        for (anchor in listOf("HOME_BALANCE", "HOME_TODAY", "HOME_BUDGET", "HOME_DECK", "HOME_CUSTOMIZE", "HOME_ADD")) {
            assertEquals("$anchor appears once", 1, Regex("""TourAnchor\.$anchor\b""").findAll(home).count())
        }
        assertFalse("the strip itself carries no tour anchor", "tourAnchor" in ui)
    }

    @Test
    fun thePageIsAKiteWatchlist_indexLine_searchBar_denseRows() {
        assertTrue("a fixed index line whose cells open the sheet", "fun IndexLine(" in ui && "IndexCell(" in ui && "IndexLine(indices)" in page)
        assertTrue("Search & add with the real limit", "Search & add" in ui && "MarketSymbols.MAX_WATCH" in ui && "SearchAddBar(watch.symbols.size)" in page)
        assertTrue("56 dp rows with tabular figures", "ROW_HEIGHT = 56.dp" in ui && "heightIn(min = ROW_HEIGHT)" in ui && "TABULAR = \"tnum\"" in ui)
        assertTrue("a row opens the sheet; long press edits", "combinedClickable(" in ui && "onLongClick = onEdit" in ui && "WatchRow(row, onOpen" in page)
        assertTrue("price coloured, change muted beneath", "color = moveColor(row)" in ui && "moveLine(row)" in ui)
        assertTrue("refresh, delayed notice, offline and loading kept", "\"Refresh\"" in page && "up to 15 minutes" in page && "Offline · showing the last prices" in page && "WatchSkeleton(" in page)
        assertTrue("skeleton while loading, retry offline", "fun WatchSkeleton" in ui && "Can't reach the price service" in ui && "Try again" in ui)
    }

    @Test
    fun noLabelledReturnsInsideComposableLambdas() {
        val composable = ui.substringAfter("fun TickerItem")
        assertFalse("D8 rejects a labelled return in a composable lambda", Regex("""return@(?!LaunchedEffect)\w+""").containsMatchIn(composable))
    }
}
