package com.zyke.message.presentation.tour

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import com.zyke.message.data.diag.BlackBox
import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourGroup
import com.zyke.message.domain.tour.TourPage
import com.zyke.message.domain.tour.TourProgress
import com.zyke.message.domain.tour.TourSeenRepository
import com.zyke.message.domain.tour.TourStep
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.domain.tour.Tours
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the overlay points at for the current step: an anchor (null: nothing, a centred card) and whether it stands in for the real thing. */
data class TourTarget(val anchor: TourAnchor? = null, val placeholder: Boolean = false)

/**
 * Runs the tour. Lives as long as the activity; the screens' own state is untouched except through [TourNavigator].
 * The step list and the progress are pure (`:domain`); this class adds what needs Android: moving the app to the place
 * a step talks about, waiting for the target to appear, and remembering the step so the tour resumes after the
 * process dies. Nothing in here may throw into the app: any failure closes the tour (marked as left, never "resume").
 */
@Stable
@Suppress("TooGenericExceptionCaught")
class TourController(
    private val repo: TourSeenRepository,
    val steps: List<TourStep> = Tours.full()
) {
    var progress by mutableStateOf<TourProgress?>(null)
        private set
    var autoPlay by mutableStateOf(repo.state.value.autoPlay)
        private set
    var target by mutableStateOf(TourTarget())
        private set
    var preparing by mutableStateOf(false)
        private set

    /** Bumped whenever the person touches the card, so auto-play starts its wait again. */
    var interaction by mutableIntStateOf(0)
        private set

    /** Bumped after each step has been prepared: lets the overlay re-register Back above any page that just opened. */
    var navVersion by mutableIntStateOf(0)
        private set

    val isRunning: Boolean get() = progress?.isActive == true
    val currentStep: TourStep? get() = progress?.current(steps)

    private var navigator: TourNavigator? = null
    private var origin: TourTab? = null
    private var shownPage: TourPage? = null

    fun attach(navigator: TourNavigator) { this.navigator = navigator }
    fun detach(navigator: TourNavigator) { if (this.navigator === navigator) this.navigator = null }

    /** Starts the tour at the beginning, or at the first step of [group]. */
    fun start(group: TourGroup? = null, autoPlay: Boolean? = null) = guarded("start") {
        if (autoPlay != null) setAutoPlay(autoPlay)
        origin = navigator?.currentTab()
        shownPage = null
        // Whatever is open (Settings, a page) is closed first, so the tour always begins on a clean tab.
        navigator?.open(null)
        val first = TourProgress()
        val from = if (group != null) first.jumpToGroup(group, steps) else first
        progress = from
        repo.update { it.started(from.index) }
    }

    /** Picks the tour up where the process left it. */
    fun resume(savedIndex: Int) = guarded("resume") {
        if (isRunning) return@guarded
        val p = TourProgress.resume(savedIndex, steps)
        origin = TourTab.HOME
        shownPage = null
        progress = p
        repo.update { it.resumed().started(p.index) }
    }

    fun next() = move { it.next(steps) }
    fun back() = move { it.back(steps) }
    fun jumpTo(group: TourGroup) = move { it.jumpToGroup(group, steps) }

    /** Leaves for good: not completed, so the banner stays available. */
    fun skip() = guarded("skip") {
        if (progress == null) return@guarded
        progress = null
        closeScene()
        repo.update { it.left() }
    }

    fun setAutoPlay(on: Boolean) {
        autoPlay = on
        repo.update { it.withAutoPlay(on) }
    }

    fun touched() { interaction++ }

    /** What is remembered about the tour; the banner and the invitation read it. */
    val seen get() = repo.state

    /** "Maybe later" on the invitation: asked once, not completed. */
    fun declineInvite() = repo.update { it.invited() }

    /** The Home banner is closed for good. */
    fun dismissBanner() = repo.update { it.bannerDismissed() }

    /** A tour recorded as running that is not going to resume (it kept failing) is closed in the record, so nothing replays it. */
    fun closeStaleRecord() = repo.update { if (it.running && !isRunning) it.left() else it }

    private fun move(change: (TourProgress) -> TourProgress) = guarded("move") {
        val now = progress ?: return@guarded
        val to = change(now)
        if (to.finished) {
            progress = null
            closeScene()
            repo.update { it.finished() }
        } else if (to != now) {
            progress = to
            repo.update { it.moved(to.index) }
        }
    }

    /** Takes the app to where [step] speaks of, then finds its target. Safe to cancel; never throws. */
    internal suspend fun prepare(step: TourStep, registry: TourRegistry) {
        preparing = true
        target = TourTarget()
        try {
            val nav = navigator
            if (nav != null) {
                val wantTab = step.tab
                val tabChanged = wantTab != null && nav.currentTab() != wantTab
                val pageChanged = step.page != shownPage
                if (pageChanged) nav.open(step.page)
                shownPage = step.page
                if (tabChanged && wantTab != null) nav.showTab(wantTab)
                delay(if (tabChanged || pageChanged) SETTLE_MS else QUICK_MS)
                if (step.page == null && wantTab != null) registry.scrollToTop(wantTab)
            }
            target = resolve(step, registry)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail("prepare ${step.id}", e)
        } finally {
            preparing = false
            navVersion++
        }
    }

    private suspend fun resolve(step: TourStep, registry: TourRegistry): TourTarget {
        val primary = step.anchor
        if (primary != null) {
            var found = awaitAnchor(registry, primary, FIND_MS)
            val tab = step.tab
            if (found == null && step.page == null && tab != null) {
                var scans = 0
                while (found == null && scans < MAX_SCANS && registry.scrollDown(tab)) {
                    scans++
                    delay(SCAN_MS)
                    found = registry.bounds[primary]?.takeIf { it.isUsable() }
                }
                if (found == null) { registry.scrollToTop(tab); delay(QUICK_MS) }
            }
            if (found != null) {
                val h = registry.windowHeight
                if (h > 0f && (found.top < 0f || found.bottom > h)) { registry.reveal(primary); delay(QUICK_MS) }
                return TourTarget(primary, placeholder = false)
            }
        }
        val fallback = step.fallbackAnchor
        if (fallback != null && awaitAnchor(registry, fallback, FIND_MS) != null) return TourTarget(fallback, placeholder = true)
        // Nothing to point at (or it never appeared): a centred card, still with its example. Never a hang.
        return TourTarget(null, placeholder = true)
    }

    private suspend fun awaitAnchor(registry: TourRegistry, anchor: TourAnchor, millis: Long): Rect? {
        val rect = withTimeoutOrNull(millis) {
            snapshotFlow { registry.bounds[anchor] }.filterNotNull().first { it.isUsable() }
        }
        if (rect != null) delay(STABLE_MS)
        return rect?.let { registry.bounds[anchor] ?: it }
    }

    private fun Rect.isUsable() = width > 1f && height > 1f

    /** Closes what the tour opened and goes back to where the person was. */
    private fun closeScene() {
        val nav = navigator
        shownPage = null
        target = TourTarget()
        try {
            nav?.open(null)
            origin?.let { if (nav?.currentTab() != it) nav?.showTab(it) }
        } catch (e: Throwable) {
            DevLog.always(TAG, "closing the tour scene failed", e)
        }
    }

    /** Runs [block]; a failure ends the tour quietly instead of reaching the app. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { fail(what, e) }
    }

    /** The tour closes itself and is marked as left, so it can neither crash-loop nor resume. */
    internal fun fail(what: String, e: Throwable) {
        DevLog.always(TAG, "tour step failed: $what", e)
        runCatching { BlackBox.mark(TAG, "$what | ${e.javaClass.simpleName}") }
        progress = null
        runCatching { closeScene() }
        runCatching { repo.update { it.left() } }
    }

    private companion object {
        const val TAG = "tour"
        const val SETTLE_MS = 420L
        const val QUICK_MS = 80L
        const val STABLE_MS = 140L
        const val FIND_MS = 1_200L
        const val SCAN_MS = 280L
        const val MAX_SCANS = 8
    }
}
