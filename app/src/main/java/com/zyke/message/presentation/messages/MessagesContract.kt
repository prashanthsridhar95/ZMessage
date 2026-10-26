package com.zyke.message.presentation.messages

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object MessagesContract {

    enum class PermissionStatus { UNKNOWN, GRANTED, DENIED }

    data class DaySection(val date: LocalDate, val messages: List<ClassifiedMessage>)

    data class State(
        val permission: PermissionStatus = PermissionStatus.UNKNOWN,
        val isLoading: Boolean = false,
        val hasError: Boolean = false,
        val messages: List<ClassifiedMessage> = emptyList(),
        val showFilters: Boolean = false,
        val selectedFilter: MessageCategory? = null,
        val expandedIds: Set<Long> = emptySet(),
        /** [messages] narrowed by [selectedFilter] and grouped by day, newest first. */
        val sections: List<DaySection> = emptyList()
    )

    sealed interface Intent {
        /** Sent once the screen is shown, with the current runtime permission state. */
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class PermissionResult(val granted: Boolean) : Intent
        data object RequestPermission : Intent
        data object ToggleFilters : Intent
        data class SelectFilter(val category: MessageCategory?) : Intent
        data class ToggleExpanded(val messageId: Long) : Intent
        data class CopyOtp(val code: String) : Intent
    }

    sealed interface Effect {
        data object LaunchPermissionRequest : Effect
        data class CopyToClipboard(val label: String, val text: String) : Effect
        data class ShowMessage(val text: String) : Effect
    }
}

internal fun List<ClassifiedMessage>.toSections(
    filter: MessageCategory?,
    zone: ZoneId = ZoneId.systemDefault()
): List<MessagesContract.DaySection> =
    asSequence()
        .filter { filter == null || it.category == filter }
        .groupBy { Instant.ofEpochMilli(it.message.date).atZone(zone).toLocalDate() }
        .map { (date, messages) -> MessagesContract.DaySection(date, messages) }
