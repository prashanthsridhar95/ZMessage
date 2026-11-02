package com.zyke.message.domain.tour

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bumped when a new major tour should be offered again to people who finished an older one. */
const val TOUR_VERSION = 1

/** How this install began, decided once at the very first start (before any setup is written) and never changed after. */
enum class TourInstallKind {
    /** A new install with nothing set up: the tour is offered up front. */
    FRESH,
    /** An older build updated in place: the person knows the app, so only a quiet banner offers the tour. */
    UPDATED,
    /** A backup was restored: same as [UPDATED]. */
    RESTORED
}

/** What the app does about the tour at start. */
enum class TourOffer { INVITE, BANNER, RESUME, NONE }

/**
 * Everything remembered about the tour, kept on this phone only and left out of backups (a restored phone must not replay it).
 * [savedIndex] is the step being shown while the tour runs (so it resumes after the process dies); [running] says one is open.
 */
data class TourState(
    val installKind: TourInstallKind? = null,
    val offered: Boolean = false,
    val completedVersion: Int = 0,
    val bannerDismissedVersion: Int = 0,
    val running: Boolean = false,
    val savedIndex: Int = 0,
    val autoPlay: Boolean = false,
    /** Times a tour was resumed at the same step without the person getting past it: a guard against a crash loop. */
    val resumeAttempts: Int = 0
) {
    val completed: Boolean get() = completedVersion >= TOUR_VERSION

    fun withKind(kind: TourInstallKind): TourState = if (installKind == null) copy(installKind = kind) else this
    fun invited(): TourState = copy(offered = true)
    fun started(fromIndex: Int): TourState = copy(offered = true, running = true, savedIndex = fromIndex)
    fun moved(index: Int): TourState = if (running && index != savedIndex) copy(savedIndex = index, resumeAttempts = 0) else this
    fun resumed(): TourState = copy(resumeAttempts = resumeAttempts + 1)
    fun finished(): TourState = copy(offered = true, running = false, savedIndex = 0, resumeAttempts = 0, completedVersion = TOUR_VERSION)
    /** Left early: not completed, so the banner can still offer it; nothing to resume. */
    fun left(): TourState = copy(offered = true, running = false, savedIndex = 0, resumeAttempts = 0)
    fun bannerDismissed(): TourState = copy(bannerDismissedVersion = TOUR_VERSION)
    /** After a restore the person is not new here: remember it, and make sure no tour is half open. */
    fun restored(): TourState = copy(installKind = TourInstallKind.RESTORED, running = false, savedIndex = 0, resumeAttempts = 0)
    fun withAutoPlay(on: Boolean): TourState = copy(autoPlay = on)
}

object TourPolicy {
    /** An install that already holds a setup, or was updated (not first installed) some time ago, is not new. */
    const val FRESH_WINDOW_MS = 120_000L

    /** A tour that keeps failing at the same step is given up on after this many resumes. */
    const val MAX_RESUMES = 2

    fun classify(hasSetupData: Boolean, millisSinceFirstInstallAtLastUpdate: Long): TourInstallKind =
        if (!hasSetupData && millisSinceFirstInstallAtLastUpdate < FRESH_WINDOW_MS) TourInstallKind.FRESH else TourInstallKind.UPDATED

    /**
     * What to do at start. A tour that was open when the process ended resumes. A fresh install that was never asked is
     * invited (after Welcome and the permission steps). Anyone who has not finished the current tour is offered the quiet
     * banner, until it is dismissed or the tour is finished.
     */
    fun offer(state: TourState): TourOffer = when {
        state.running && state.savedIndex > 0 && state.resumeAttempts < MAX_RESUMES -> TourOffer.RESUME
        state.completed -> TourOffer.NONE
        state.installKind == TourInstallKind.FRESH && !state.offered -> TourOffer.INVITE
        state.bannerDismissedVersion >= TOUR_VERSION -> TourOffer.NONE
        state.installKind == null -> TourOffer.NONE
        else -> TourOffer.BANNER
    }
}

/** Where the tour state lives. The app keeps it in a private preferences file; tests and previews use [InMemoryTourSeenRepository]. */
interface TourSeenRepository {
    val state: StateFlow<TourState>
    fun update(transform: (TourState) -> TourState)
}

/** Remembers nothing: for places that must not touch storage. */
object NoTourSeenRepository : TourSeenRepository {
    private val flow = MutableStateFlow(TourState())
    override val state: StateFlow<TourState> = flow
    override fun update(transform: (TourState) -> TourState) = Unit
}

class InMemoryTourSeenRepository(initial: TourState = TourState()) : TourSeenRepository {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<TourState> = flow
    override fun update(transform: (TourState) -> TourState) { flow.value = transform(flow.value) }
}
