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
        for (anchor in listOf("HOME_BALANCE", "HOME_TODAY", "HOME_BUDGET", "HOME_DECK", "HOME_CUSTOMIZE")) {
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

    private fun main(rel: String) = file(rel).readText()

    @Test
    fun theListCanBeGroupedByMarket_flagNameIndexAndHoursHint() {
        assertTrue("the chip shows only when the list spans more than one market", "MarketGrouping.offered(state.rows)" in page && "FilterChip(" in page)
        assertTrue("the choice is kept, default decided by the list", "MarketGrouping.shown(watch.groupByMarket, state.rows)" in page && "ticker.setGroupByMarket(" in page)
        assertTrue("rows are grouped from the same rows, indices heading their group", "MarketGrouping.group(list, indexSource = state.rows)" in page)
        val header = ui.substringAfter("private fun MarketHeader(").substringBefore("/** Placeholder rows")
        assertTrue("flag, country name, hours hint and the index move", "market.flag" in header && "market.title" in header && "MarketCalendar.hint(" in header && "indexText" in header)
        assertTrue("one heading for a screen reader, flag emoji not read out", "heading()" in header && "clearAndSetSemantics" in header)
        assertTrue("index moves under the name at 320 dp and large text", "homeStacked()" in header)
        assertTrue("rows keep tap, long press, hide, loading and offline", "WatchRow(row, onOpen" in page && "onEdit = { editing = true }" in page && "WatchSkeleton(" in page && "WatchEmpty(" in page)
    }

    @Test
    fun marketCloseSummary_perMarketSwitches_offset_openMarketWatch_noNewPermissionOrHost() {
        val settings = main("com/zyke/message/presentation/markets/MarketSettings.kt")
        assertTrue("a switch per market and the offset", "MarketCloseSummary.MARKETS.forEach" in settings && "MarketCloseSummary.OFFSETS.forEach" in settings && "closeOffsetMinutes" in settings)
        assertTrue("notification permission asked only when a switch is turned on", "POST_NOTIFICATIONS" in settings && "launcher.launch(" in settings)
        val scheduler = main("com/zyke/message/notifications/MarketCloseScheduler.kt")
        assertTrue("inexact alarms only, like the digest", "setAndAllowWhileIdle" in scheduler && "setExact" !in scheduler && "SCHEDULE_EXACT_ALARM" !in scheduler)
        assertTrue("an alarm per market, India keeps its old request codes", "811" in scheduler && "814" in scheduler && "EXTRA_MARKET" in scheduler)
        val receiver = main("com/zyke/message/notifications/MarketCloseReceiver.kt")
        assertTrue("never in the demo, and only while that market's switch is on", "DemoMode.active" in receiver && "settings.closeActive(market)" in receiver)
        assertTrue("BigTextStyle, zyke_icon, silent, opens Market watch", "BigTextStyle()" in receiver && "R.drawable.zyke_icon" in receiver && "setSilent(true)" in receiver && "MarketRequests.ACTION_OPEN" in receiver)
        assertTrue("one notification id per market so one never replaces the other", "NOTIFICATION_ID_INDIA" in receiver && "NOTIFICATION_ID_US" in receiver)
        assertTrue("an alarm from before the update means India", "getOrDefault(Market.INDIA)" in receiver)
        assertTrue("Home opens the page when asked", "MarketRequests.pending" in main("com/zyke/message/presentation/home/HomeScreen.kt") && "MarketRequests.fromIntent" in main("com/zyke/message/presentation/MainActivity.kt"))
        val repo = main("com/zyke/message/data/repository/WatchlistRepositoryImpl.kt")
        assertTrue("the old single switch is India's, same key", "marketCloseSummary = prefs.getBoolean(CLOSE_SUMMARY, false)" in repo && "CLOSE_SUMMARY = \"close_summary\"" in repo)
        assertFalse("no new host: only the existing quote fetcher is used", Regex("https?://").containsMatchIn(receiver + scheduler + settings))
    }
}
