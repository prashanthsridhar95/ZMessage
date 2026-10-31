package com.zyke.message.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** What kind of thing in the plan section a dismissal or snooze belongs to. */
enum class PlanKind { AGENDA, BILL, REMINDER }

/**
 * One thing the person put out of sight. A null [until] means dismissed for good; otherwise it comes back on that day
 * (and is hidden on every day before it).
 */
data class PlanHide(val key: String, val label: String, val kind: PlanKind, val until: LocalDate?) {
    val isSnooze: Boolean get() = until != null
}

/** Everything put out of sight, by key. */
data class PlanActionState(val hides: Map<String, PlanHide> = emptyMap()) {
    val isEmpty: Boolean get() = hides.isEmpty()
}

/** The ready-made snooze lengths. A picked date is passed straight to [PlanActions.snooze]. */
enum class SnoozeOption(val label: String) {
    TOMORROW("1 day"),
    THREE_DAYS("3 days"),
    NEXT_WEEK("Next week");

    /** The day the item comes back. Next week is the coming Monday. */
    fun until(today: LocalDate): LocalDate = when (this) {
        TOMORROW -> today.plusDays(1)
        THREE_DAYS -> today.plusDays(3)
        NEXT_WEEK -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    }
}

/** Dismissing and snoozing the things in the plan section: pure, so it can be tested without a phone. */
object PlanActions {
    fun key(kind: PlanKind, id: String): String = "${kind.name.lowercase()}:$id"

    /** Whether the hide still holds on [today]: dismissed for good, or snoozed to a day that has not come. */
    fun isActive(hide: PlanHide, today: LocalDate): Boolean = hide.until == null || hide.until.isAfter(today)

    /** The hide on [key] that holds today, or null: the item is showing. */
    fun hideFor(key: String, state: PlanActionState, today: LocalDate): PlanHide? =
        state.hides[key]?.takeIf { isActive(it, today) }

    /** [items] without those dismissed, or snoozed to a day that has not come. Order is kept. */
    fun <T> visible(items: List<T>, state: PlanActionState, today: LocalDate, keyOf: (T) -> String): List<T> =
        if (state.isEmpty) items else items.filter { hideFor(keyOf(it), state, today) == null }

    /** What is out of sight today, newest-to-return first and dismissed last, for the "Snoozed & dismissed" list. */
    fun hidden(state: PlanActionState, today: LocalDate): List<PlanHide> =
        state.hides.values.filter { isActive(it, today) }
            .sortedWith(compareBy<PlanHide> { it.until == null }.thenBy { it.until }.thenBy { it.label.lowercase() })

    fun dismiss(state: PlanActionState, key: String, label: String, kind: PlanKind): PlanActionState =
        PlanActionState(state.hides + (key to PlanHide(key, label, kind, null)))

    /** Snoozing to a day that is not after [today] would change nothing, so it is taken as tomorrow. */
    fun snooze(state: PlanActionState, key: String, label: String, kind: PlanKind, until: LocalDate, today: LocalDate): PlanActionState {
        val day = if (until.isAfter(today)) until else today.plusDays(1)
        return PlanActionState(state.hides + (key to PlanHide(key, label, kind, day)))
    }

    fun restore(state: PlanActionState, key: String): PlanActionState =
        if (key in state.hides) PlanActionState(state.hides - key) else state

    /** Drops the snoozes whose day has come, so the store does not grow. Dismissals stay until restored. */
    fun clean(state: PlanActionState, today: LocalDate): PlanActionState =
        if (state.hides.values.all { isActive(it, today) }) state
        else PlanActionState(state.hides.filterValues { isActive(it, today) })
}

/** The key each kind of plan item is dismissed or snoozed under: stable across launches, and one per occurrence. */
object PlanKeys {
    fun agenda(item: com.zyke.message.domain.life.AgendaItem): String =
        PlanActions.key(PlanKind.AGENDA, "${item.message.id}-${item.kind.name}")

    /** One per bill and due date, so next month's bill is not hidden by this month's snooze. */
    fun bill(item: com.zyke.message.domain.home.UpcomingItem): String = PlanActions.key(PlanKind.BILL, item.markedPaidKey)

    fun reminder(reminder: com.zyke.message.domain.reminders.Reminder): String = PlanActions.key(PlanKind.REMINDER, reminder.id)
}
