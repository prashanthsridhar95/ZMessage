package com.zyke.message.domain.repository

import com.zyke.message.domain.plan.PlanActionState
import com.zyke.message.domain.plan.PlanKind
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the person dismissed or snoozed in the plan section; kept across launches. */
interface PlanActionRepository {
    val state: StateFlow<PlanActionState>

    fun dismiss(key: String, label: String, kind: PlanKind)

    fun snooze(key: String, label: String, kind: PlanKind, until: LocalDate, today: LocalDate)

    fun restore(key: String)

    /** Forgets the snoozes that have run out. */
    fun clean(today: LocalDate)
}

object NoPlanActions : PlanActionRepository {
    override val state: StateFlow<PlanActionState> = MutableStateFlow(PlanActionState())
    override fun dismiss(key: String, label: String, kind: PlanKind) = Unit
    override fun snooze(key: String, label: String, kind: PlanKind, until: LocalDate, today: LocalDate) = Unit
    override fun restore(key: String) = Unit
    override fun clean(today: LocalDate) = Unit
}
