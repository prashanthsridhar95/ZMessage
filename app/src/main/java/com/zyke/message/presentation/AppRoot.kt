package com.zyke.message.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R

/** The three places the app lives: the overview, the money picture, and the inbox. */
enum class AppTab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.baseline_home_24),
    MONEY("Money", R.drawable.baseline_wallet_24),
    INBOX("Inbox", R.drawable.baseline_message_24)
}

/**
 * Tab shell. Each tab keeps its scroll position while another is showing, and Back from any
 * tab returns to Home before leaving the app, so the way out is always the same.
 */
@Composable
fun AppRoot(
    inboxBadge: Int,
    content: @Composable (tab: AppTab, modifier: Modifier, navigate: (AppTab) -> Unit) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    val stateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = tab != AppTab.HOME) { tab = AppTab.HOME }

    Scaffold(
        // Each screen handles the status bar itself; the bar below handles the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { AppBottomBar(selected = tab, inboxBadge = inboxBadge, onSelect = { tab = it }) }
    ) { padding ->
        stateHolder.SaveableStateProvider(tab.name) {
            content(tab, Modifier.padding(padding)) { tab = it }
        }
    }
}

@Composable
fun AppBottomBar(selected: AppTab, inboxBadge: Int, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(
            Modifier.padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        if (tab == AppTab.INBOX && inboxBadge > 0) {
                            BadgedBox(badge = { Badge { Text(if (inboxBadge > 99) "99+" else inboxBadge.toString()) } }) {
                                Icon(painterResource(tab.icon), contentDescription = null)
                            }
                        } else {
                            Icon(painterResource(tab.icon), contentDescription = null)
                        }
                    },
                    label = { Text(tab.label) }
                )
            }
        }
    }
}
