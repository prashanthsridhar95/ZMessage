package com.zyke.message.presentation.tour

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourTab

/**
 * Where the tour's targets are on screen. The map is read only by the overlay (one snapshot read per frame while a step
 * is shown), so layout of the screens underneath never recomposes anything but the overlay.
 */
@OptIn(ExperimentalFoundationApi::class)
class TourRegistry {
    /** Window coordinates of each registered anchor. Empty while no tour runs: the modifier registers nothing then. */
    val bounds = mutableStateMapOf<TourAnchor, Rect>()
    internal val requesters = HashMap<TourAnchor, BringIntoViewRequester>()
    internal val scrollers = HashMap<TourTab, LazyListState>()
    var windowWidth by mutableStateOf(0f)
    var windowHeight by mutableStateOf(0f)

    /** Brings a partly hidden target into view if it sits in a scrolling parent; best effort. */
    @OptIn(ExperimentalFoundationApi::class)
    internal suspend fun reveal(anchor: TourAnchor) {
        try { requesters[anchor]?.bringIntoView() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
    }

    /** Scrolls the tab's list back to the top, so each step starts from the same place. */
    internal suspend fun scrollToTop(tab: TourTab) {
        try { scrollers[tab]?.scrollToItem(0) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
    }

    /** Scrolls the tab's list one screenful (about 60%) further down; false when there is no list or it is at the end. */
    internal suspend fun scrollDown(tab: TourTab): Boolean = try {
        val list = scrollers[tab]
        if (list == null || !list.canScrollForward) false else { list.animateScrollBy(windowHeight * SCAN_STEP); true }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { false }

    private companion object { const val SCAN_STEP = 0.6f }
}

/** The registry, provided by the app shell while it is composed. */
val LocalTourRegistry = compositionLocalOf<TourRegistry?> { null }

/** True while a tour is running. Anchors register only then, so the modifier costs nothing the rest of the time. */
val LocalTourActive = compositionLocalOf { false }

/** The running tour, for screens that start it (Settings) or the banner. Null where there is none. */
val LocalTour = compositionLocalOf<TourController?> { null }

/**
 * Marks this element as a place the tour can point at. A no-op unless a tour is running. Each [TourAnchor] value is
 * used on exactly one element; `TourPolicyTest` checks that.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tourAnchor(id: TourAnchor): Modifier = composed {
    val registry = LocalTourRegistry.current
    if (registry == null || !LocalTourActive.current) {
        Modifier
    } else {
        val requester = remember { BringIntoViewRequester() }
        DisposableEffect(id, registry) {
            registry.requesters[id] = requester
            onDispose { registry.requesters.remove(id); registry.bounds.remove(id) }
        }
        Modifier.bringIntoViewRequester(requester).onGloballyPositioned { coordinates ->
            if (coordinates.isAttached) {
                val rect = coordinates.boundsInWindow()
                if (registry.bounds[id] != rect) registry.bounds[id] = rect
            }
        }
    }
}

/** Lets the tour scroll this tab's list to find a target that is further down. Does nothing unless a tour is running. */
@Composable
fun TourScroll(tab: TourTab, state: LazyListState) {
    val registry = LocalTourRegistry.current
    if (registry == null || !LocalTourActive.current) return
    DisposableEffect(tab, state, registry) {
        registry.scrollers[tab] = state
        onDispose { if (registry.scrollers[tab] === state) registry.scrollers.remove(tab) }
    }
}

/**
 * Settings keeps which page is open to itself; while the tour runs it asks for one through this. [active] false means
 * Settings is on its own again. Read by `SettingsPage` (one line there).
 */
object TourSettingsRequest {
    var active by mutableStateOf(false)
    var group by mutableStateOf<String?>(null)
}

/** What the app shell lets the tour do: move between tabs and open or close pages. All calls are cheap and safe to repeat. */
interface TourNavigator {
    fun currentTab(): TourTab
    fun showTab(tab: TourTab)
    /** Closes any page the tour opened and opens [page] (null: just closes). */
    fun open(page: com.zyke.message.domain.tour.TourPage?)
}

/** Home's own pages (Calendar, Goals, Reminders) are opened through the Home screen's state; MainActivity implements this. */
interface TourHomeNav {
    fun open(page: com.zyke.message.domain.tour.TourPage)
    fun close(page: com.zyke.message.domain.tour.TourPage)
}
