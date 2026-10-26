package com.zyke.message.presentation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.zyke.message.presentation.theme.ZykeMessageTheme

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

        setContent {
            ZykeMessageTheme {
                val home by homeViewModel.state.collectAsStateWithLifecycle()

                AppRoot(inboxBadge = home.unreadCount) { tab, modifier, navigate ->
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
}
