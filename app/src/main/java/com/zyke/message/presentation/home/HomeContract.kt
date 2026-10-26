package com.zyke.message.presentation.home

import com.zyke.message.domain.dashboard.CategoryTotal
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.UserMessageState
import java.math.BigDecimal
import java.time.Instant

object HomeContract {

    data class State(
        val isLoading: Boolean = true,
        val permissionMissing: Boolean = false,
        val hasError: Boolean = false,
        val hasLoaded: Boolean = false,

        // Inputs
        val messages: List<ClassifiedMessage> = emptyList(),
        val userState: UserMessageState = UserMessageState(),
        val budgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        val now: Instant = Instant.EPOCH,

        // Derived
        val attention: List<AttentionItem> = emptyList(),
        /** This month's spending, against last month. */
        val thisMonth: Dashboard? = null,
        val upcoming: List<UpcomingItem> = emptyList(),
        /** Budgeted categories, the most used first. */
        val budgetStatus: List<CategoryTotal> = emptyList(),
        val recent: List<ClassifiedMessage> = emptyList(),
        val unreadCount: Int = 0
    ) {
        /** Nothing money-related has been seen yet. */
        val isBlank: Boolean
            get() = hasLoaded && attention.isEmpty() && upcoming.isEmpty() && recent.isEmpty() && thisMonth?.isEmpty != false
    }

    sealed interface Intent {
        data class ScreenStarted(val permissionGranted: Boolean) : Intent
        data class CopyOtp(val messageId: Long, val code: String) : Intent
        data class DismissAttention(val key: String) : Intent
        data class OpenLink(val url: String) : Intent
    }

    sealed interface Effect {
        data class CopyToClipboard(val label: String, val text: String) : Effect
        data class OpenLink(val url: String) : Effect
    }
}
