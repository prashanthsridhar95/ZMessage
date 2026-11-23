package com.zyke.message.domain.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The optional parts of Home. The balance, the Today card and the footer are always there. */
enum class HomeSection(val id: String, val title: String, val summary: String, val defaultOn: Boolean) {
    BUDGET("budget", "Today's budget row", "What is left to spend today, one line", true),
    ACTIVITY("activity", "Recent activity", "Your last three payments", true),
    DECK("deck", "Your deck", "Stacks of new messages to swipe through", true),
    MARKET("market", "Market strip", "Prices of the shares you watch (needs Market watch on)", false),
    GOALS("goals", "Goals and reminders", "What is coming up in your plans", false),
    SHORTCUTS("shortcuts", "Shortcut tiles", "Calendar, Goals, Reminders and Can I afford it?", false),
    QUICK_ADDS("quickadds", "Quick adds prompt", "Things found in your messages to add", false);

    companion object {
        fun byId(id: String): HomeSection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which optional parts of Home are shown. Only choices that differ from the calm default are kept, so two layouts that mean the same
 * are equal once [normalized]. Unknown ids are ignored.
 */
data class HomeLayout(val choices: Map<String, Boolean> = emptyMap()) {
    fun shows(section: HomeSection): Boolean = choices[section.id] ?: section.defaultOn

    fun with(section: HomeSection, on: Boolean): HomeLayout = HomeLayout(choices + (section.id to on)).normalized()

    fun normalized(): HomeLayout =
        HomeLayout(HomeSection.entries.filter { choices[it.id] != null && choices[it.id] != it.defaultOn }.associate { it.id to !it.defaultOn })

    val isCalm: Boolean get() = normalized().choices.isEmpty()

    fun reset(): HomeLayout = CALM

    companion object {
        val CALM = HomeLayout()
    }
}

/** Where the person's Home layout is kept. On this phone only. */
interface HomeLayoutRepository {
    val layout: StateFlow<HomeLayout>
    fun save(layout: HomeLayout)
    fun reset() = save(HomeLayout.CALM)
}

object NoHomeLayout : HomeLayoutRepository {
    override val layout: StateFlow<HomeLayout> = MutableStateFlow(HomeLayout.CALM)
    override fun save(layout: HomeLayout) = Unit
}
