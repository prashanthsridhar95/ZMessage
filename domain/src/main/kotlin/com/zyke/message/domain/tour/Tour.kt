package com.zyke.message.domain.tour

/** The five bottom tabs, as the tour knows them (the app maps each to its own tab). */
enum class TourTab { HOME, ACCOUNTS, INSIGHTS, BILLS, MESSAGES }

/**
 * Pages that can be opened deterministically while the tour is running. The Settings pages are one per Settings group
 * (named after it); everything else is a page of its own.
 */
enum class TourPage {
    SETTINGS_HUB, SETTINGS_MONEY, SETTINGS_LIMITS, SETTINGS_CARDS, SETTINGS_RULES, SETTINGS_ALERTS, SETTINGS_PRIVACY,
    SETTINGS_APPEARANCE, SETTINGS_ABOUT, SETTINGS_HELP, SEARCH, CALENDAR, GOALS, REMINDERS;

    val isSettings: Boolean get() = name.startsWith("SETTINGS_")

    /** The Settings group a Settings page shows ("PRIVACY" for [SETTINGS_PRIVACY]); null for the hub and for other pages. */
    val settingsGroup: String? get() = if (isSettings && this != SETTINGS_HUB) name.removePrefix("SETTINGS_") else null
}

/**
 * Things in the real UI the tour can point at. Each value is attached to exactly one element in the app
 * (`Modifier.tourAnchor`), which `TourPolicyTest` enforces. The ones for always-present elements are used as fallbacks.
 */
enum class TourAnchor {
    NAV_BAR, NAV_HOME, NAV_ACCOUNTS, NAV_INSIGHTS, NAV_BILLS, NAV_MESSAGES,
    TOOLBAR_SEARCH, TOOLBAR_EYE, TOOLBAR_SETTINGS, TOOLBAR_FILTER_INSIGHTS,
    HOME_BALANCE, HOME_BUDGET, HOME_CODES, HOME_REVIEW, HOME_NEXT_DUE, HOME_SHORTCUTS, HOME_ADD,
    ACCOUNTS_TABS, ACCOUNTS_OVERVIEW, ACCOUNTS_DECK, INVESTMENTS_ADD,
    INSIGHTS_MONTH, INSIGHTS_FORECAST, INSIGHTS_REPORT_CARD, INSIGHTS_CATEGORIES, INSIGHTS_SUBSCRIPTIONS,
    BILLS_DUE, BILLS_REPEATS, BILLS_FRIENDS,
    MESSAGES_FEED, MESSAGES_SEARCH,
    SEARCH_FIELD,
    SETTINGS_GROUP_BODY, SETTINGS_BACKUP
}

/** The small made-up example shown in the coach card when the real thing is not on screen yet (a fresh install has no data). */
enum class TourPreviewKind {
    BALANCE, TODAY_BUDGET, CODE, NEEDS_REVIEW, PLAN_ROW, ACCOUNT_CARD, NET_WORTH, MONTH_COMPARE, HEADING, REPORT_CARD,
    CATEGORY_BARS, BILL_ROW, REPEATS, FRIEND_SPLIT, MESSAGE_ROW, MESSAGE_DETAIL, SUBSCRIPTION, INVESTMENT
}

/** Chapters. The Settings screen lists these; each begins at its first step. */
enum class TourGroup(val chapter: String) {
    WELCOME("Welcome"), HOME("Home"), ACCOUNTS("Accounts"), INSIGHTS("Insights"), BILLS("Bills"), MESSAGES("Messages"),
    SEARCH("Search"), SETTINGS("Settings & privacy"), BACKUP("Backup"), EXTRAS("Widgets & Watch")
}

/**
 * One stop of the tour. [anchor] is the real element to spotlight; for a step about something that only exists once
 * there is data ([needsData]), the real element may be missing, and then [fallbackAnchor] (something always on screen) is
 * spotlighted and the [preview] example is drawn in the card instead. [covers] lists the feature keys the step explains.
 */
data class TourStep(
    val id: String,
    val group: TourGroup,
    val title: String,
    val body: String,
    val tab: TourTab? = null,
    val anchor: TourAnchor? = null,
    val page: TourPage? = null,
    val spotlightHint: String? = null,
    val needsData: Boolean = false,
    val actionLabel: String? = null,
    val fallbackAnchor: TourAnchor? = null,
    val preview: TourPreviewKind? = null,
    val tip: String? = null,
    val covers: Set<String> = emptySet()
) {
    /** Auto-play never moves on from a step that asks the person to do something. */
    val needsAction: Boolean get() = actionLabel != null
}

/**
 * Where the person is in the tour. Immutable; every move returns a new value, so it is trivial to test and to save.
 * [index] points into the step list; [finished] and [skipped] end the tour.
 */
data class TourProgress(
    val index: Int = 0,
    val completed: Set<String> = emptySet(),
    val finished: Boolean = false,
    val skipped: Boolean = false
) {
    val isActive: Boolean get() = !finished && !skipped

    fun current(steps: List<TourStep>): TourStep? = if (isActive) steps.getOrNull(index) else null

    /** Marks the current step done and moves on; the last step ends the tour as finished. */
    fun next(steps: List<TourStep>): TourProgress {
        if (!isActive || steps.isEmpty()) return this
        val i = index.coerceIn(0, steps.lastIndex)
        val done = completed + steps[i].id
        return if (i >= steps.lastIndex) copy(index = steps.lastIndex, completed = done, finished = true) else copy(index = i + 1, completed = done)
    }

    fun back(steps: List<TourStep>): TourProgress = if (!isActive || steps.isEmpty()) this else copy(index = (index - 1).coerceIn(0, steps.lastIndex))

    /** Leaves the tour without completing it; what was seen stays recorded. */
    fun skip(): TourProgress = if (finished) this else copy(skipped = true)

    /** Jumps to the first step of [group]; steps before it are not marked done. A group with no steps changes nothing. */
    fun jumpToGroup(group: TourGroup, steps: List<TourStep>): TourProgress {
        val at = steps.indexOfFirst { it.group == group }
        return if (at < 0) this else copy(index = at, finished = false, skipped = false)
    }

    /** Which chapter the current step belongs to. */
    fun group(steps: List<TourStep>): TourGroup? = steps.getOrNull(index)?.group

    /** Steps left after this one. */
    fun remaining(steps: List<TourStep>): Int = (steps.lastIndex - index).coerceAtLeast(0)

    /** Position inside the current chapter, 1-based, and the chapter's length: for the segmented progress bar. */
    fun positionInGroup(steps: List<TourStep>): Pair<Int, Int> {
        val group = group(steps) ?: return 0 to 0
        val inGroup = steps.withIndex().filter { it.value.group == group }
        return (inGroup.indexOfFirst { it.index == index } + 1) to inGroup.size
    }

    companion object {
        /** A progress restored from a saved index; an index that no longer fits (the tour changed) starts over. */
        fun resume(savedIndex: Int, steps: List<TourStep>): TourProgress =
            if (savedIndex in 1..steps.lastIndex) TourProgress(index = savedIndex, completed = steps.take(savedIndex).map { it.id }.toSet()) else TourProgress()
    }
}

/** How long auto-play stays on a step: longer bodies take longer to read. Pure, so it is tested. */
object TourTiming {
    const val MIN_MS = 5_000L
    const val MAX_MS = 14_000L
    private const val BASE_MS = 3_500L
    private const val PER_CHAR_MS = 38L

    fun dwellMillis(step: TourStep): Long = (BASE_MS + (step.title.length + step.body.length + (step.tip?.length ?: 0)) * PER_CHAR_MS).coerceIn(MIN_MS, MAX_MS)
}
