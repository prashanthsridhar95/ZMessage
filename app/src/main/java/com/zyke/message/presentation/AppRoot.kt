package com.zyke.message.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
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
import com.zyke.message.presentation.dashboard.DashboardRoute
import com.zyke.message.presentation.dashboard.DashboardViewModel
import com.zyke.message.presentation.messages.MessagesRoute
import com.zyke.message.presentation.messages.MessagesViewModel

enum class AppTab(val label: String, val icon: Int) {
    MESSAGES("Messages", R.drawable.baseline_message_24),
    DASHBOARD("Dashboard", R.drawable.baseline_dashboard_24)
}

/** Two-tab shell; each tab keeps its scroll position while the other is showing. */
@Composable
fun AppRoot(messagesViewModel: MessagesViewModel, dashboardViewModel: DashboardViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.MESSAGES) }
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        // Each screen handles the status bar itself; the bar below handles the navigation bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { AppBottomBar(selected = tab, onSelect = { tab = it }) }
    ) { padding ->
        stateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                AppTab.MESSAGES -> MessagesRoute(messagesViewModel, Modifier.padding(padding))
                AppTab.DASHBOARD -> DashboardRoute(dashboardViewModel, Modifier.padding(padding))
            }
        }
    }
}

@Composable
fun AppBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
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
                    icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                    label = { Text(tab.label) }
                )
            }
        }
    }
}
