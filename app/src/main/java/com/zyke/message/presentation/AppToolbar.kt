package com.zyke.message.presentation

import androidx.compose.runtime.Composable
import com.zyke.message.R
import com.zyke.message.presentation.common.ToolbarSearch
import com.zyke.message.presentation.common.ZykeToolbar
import com.zyke.message.presentation.messages.components.BarIcon

/** What the Inbox contributes to the toolbar. */
data class InboxToolbarState(
    val buttonsVisible: Boolean = false,
    val searching: Boolean = false,
    val query: String = "",
    val filterCount: Int = 0
)

/** What Money contributes to the toolbar. */
data class MoneyToolbarState(
    val buttonsVisible: Boolean = false,
    val filterCount: Int = 0
)

class ToolbarCallbacks(
    val onToggleSearch: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onInboxFilters: () -> Unit,
    val onMoneyFilters: () -> Unit
)

/** The Zyke toolbar with the buttons that belong to the tab being shown. */
@Composable
fun AppToolbar(
    tab: AppTab,
    inbox: InboxToolbarState,
    money: MoneyToolbarState,
    callbacks: ToolbarCallbacks,
    onOpenSettings: () -> Unit
) {
    when {
        tab == AppTab.INBOX && inbox.searching -> ZykeToolbar(
            search = ToolbarSearch(inbox.query, callbacks.onQueryChange, callbacks.onToggleSearch)
        )
        else -> ZykeToolbar {
            if (tab == AppTab.INBOX && inbox.buttonsVisible) {
                BarIcon(R.drawable.baseline_search_24, "Search", false, 0, callbacks.onToggleSearch)
                BarIcon(
                    R.drawable.baseline_filter_alt_24, "Filter messages",
                    inbox.filterCount > 0, inbox.filterCount, callbacks.onInboxFilters
                )
            }
            if (tab == AppTab.MONEY && money.buttonsVisible) {
                BarIcon(
                    R.drawable.baseline_filter_alt_24, "Filter by category",
                    money.filterCount > 0, money.filterCount, callbacks.onMoneyFilters
                )
            }
            // On the Inbox the search button beside it searches the messages; everywhere else this searches everything.
            if (!(tab == AppTab.INBOX && inbox.buttonsVisible)) {
                BarIcon(R.drawable.ic_search_24, "Search everything", false, 0, com.zyke.message.presentation.common.LocalOpenSearch.current)
            }
            val privacy = com.zyke.message.presentation.common.LocalPrivacyMode.current
            BarIcon(
                if (privacy.hidden) R.drawable.ic_visibility_off_24 else R.drawable.ic_visibility_24,
                if (privacy.hidden) "Amounts hidden, tap to show" else "Hide amounts", privacy.hidden, 0, privacy.toggle
            )
            BarIcon(R.drawable.baseline_settings_24, "Settings", false, 0, onOpenSettings)
        }
    }
}
