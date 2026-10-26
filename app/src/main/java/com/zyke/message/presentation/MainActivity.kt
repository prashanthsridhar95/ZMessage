package com.zyke.message.presentation

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.presentation.dashboard.DashboardContract
import com.zyke.message.presentation.dashboard.DashboardRoute
import com.zyke.message.presentation.dashboard.DashboardViewModel
import com.zyke.message.presentation.home.HomeContract
import com.zyke.message.presentation.home.HomeRoute
import com.zyke.message.presentation.home.HomeViewModel
import com.zyke.message.presentation.messages.MessagesContract
import com.zyke.message.presentation.messages.MessagesRoute
import com.zyke.message.presentation.messages.MessagesViewModel
import com.zyke.message.presentation.settings.SettingsRoute
import com.zyke.message.presentation.settings.SettingsViewModel
import com.zyke.message.presentation.theme.ZykeMessageTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as ZykeMessagesApplication).container

    private val messagesViewModel: MessagesViewModel by viewModels {
        MessagesViewModel.Factory(container.observeMessages, container.userMessageState)
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(container.observeMessages, container.budgets)
    }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(container.observeMessages, container.userMessageState, container.budgets)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            container.observeMessages, container.settings, container.userMessageState, container.budgets
        )
    }

    /** One permission prompt for the whole app; every tab is told the answer. */
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        messagesViewModel.onIntent(MessagesContract.Intent.PermissionResult(granted))
        if (granted) {
            homeViewModel.onIntent(HomeContract.Intent.ScreenStarted(true))
            dashboardViewModel.onIntent(DashboardContract.Intent.ScreenStarted(true))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyScreenProtection(container.settings.settings.value.screenProtection)
        observeScreenProtection()

        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()

        setContent {
            ZykeMessageTheme {
                val home by homeViewModel.state.collectAsStateWithLifecycle()
                val inbox by messagesViewModel.state
                    .map { it.toToolbarState() }
                    .distinctUntilChanged()
                    .collectAsStateWithLifecycle(InboxToolbarState())
                val money by dashboardViewModel.state
                    .map { MoneyToolbarState(it.dashboard != null && !it.permissionMissing && !it.hasError, it.activeFilterCount) }
                    .distinctUntilChanged()
                    .collectAsStateWithLifecycle(MoneyToolbarState())

                val toolbarCallbacks = remember {
                    ToolbarCallbacks(
                        onToggleSearch = { messagesViewModel.onIntent(MessagesContract.Intent.ToggleSearch) },
                        onQueryChange = { messagesViewModel.onIntent(MessagesContract.Intent.SetQuery(it)) },
                        onInboxFilters = { messagesViewModel.onIntent(MessagesContract.Intent.OpenFilters) },
                        onMoneyFilters = { dashboardViewModel.onIntent(DashboardContract.Intent.OpenFilters) }
                    )
                }

                AppRoot(
                    inboxBadge = home.unreadCount,
                    toolbar = { tab, openSettings ->
                        AppToolbar(
                            tab = tab,
                            inbox = inbox,
                            money = money,
                            callbacks = toolbarCallbacks,
                            onOpenSettings = openSettings
                        )
                    },
                    settings = { onDismiss ->
                        SettingsRoute(settingsViewModel, versionName, onDismiss)
                    }
                ) { tab, modifier, navigate ->
                    when (tab) {
                        AppTab.HOME -> HomeRoute(
                            viewModel = homeViewModel,
                            onNavigate = navigate,
                            onRequestPermission = { smsPermission.launch(Manifest.permission.READ_SMS) },
                            modifier = modifier
                        )
                        AppTab.MONEY -> DashboardRoute(
                            viewModel = dashboardViewModel,
                            onRequestPermission = { smsPermission.launch(Manifest.permission.READ_SMS) },
                            modifier = modifier
                        )
                        AppTab.INBOX -> MessagesRoute(messagesViewModel, modifier)
                    }
                }
            }
        }
    }

    private fun observeScreenProtection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.settings.collect { applyScreenProtection(it.screenProtection) }
            }
        }
    }

    /** FLAG_SECURE blocks screenshots, screen recording and the recent-apps thumbnail. */
    private fun applyScreenProtection(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Buttons are only offered once the screen underneath has something to filter or search. */
private fun MessagesContract.State.toToolbarState() = InboxToolbarState(
    buttonsVisible = permission == MessagesContract.PermissionStatus.GRANTED && !isLoading && !hasError && thread == null,
    searching = isSearching,
    query = query,
    filterCount = filter.extraCount
)
