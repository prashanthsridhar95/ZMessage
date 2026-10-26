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

/** The tab the app opens on, and the one Back returns to before leaving. */
val StartTab = AppTab.INBOX

/**
 * Tab shell. The app opens on the Inbox. Each tab keeps its scroll position while another is showing,
 * and Back from any other tab returns to the Inbox before leaving the app, so the way out is always
 * the same.
 */
@Composable
fun AppRoot(
    inboxBadge: Int,
    toolbar: @Composable (tab: AppTab, settingsOpen: Boolean, openSettings: () -> Unit) -> Unit,
    settings: @Composable (modifier: Modifier, onBack: () -> Unit) -> Unit,
    content: @Composable (tab: AppTab, modifier: Modifier, navigate: (AppTab) -> Unit) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(StartTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = tab != StartTab) { tab = StartTab }
    // Declared last so it wins: Back closes Settings before anything else.
    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        // The Zyke toolbar handles the status bar and the bottom bar the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { toolbar(tab, showSettings) { showSettings = true } },
        bottomBar = { AppBottomBar(selected = tab, inboxBadge = inboxBadge, onSelect = { tab = it; showSettings = false }) }
    ) { padding ->
        if (showSettings) {
            settings(Modifier.padding(padding)) { showSettings = false }
        } else {
            stateHolder.SaveableStateProvider(tab.name) {
                content(tab, Modifier.padding(padding)) { tab = it }
            }
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
