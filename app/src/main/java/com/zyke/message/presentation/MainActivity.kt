package com.zyke.message.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.presentation.dashboard.DashboardViewModel
import com.zyke.message.presentation.messages.MessagesViewModel
import com.zyke.message.presentation.theme.ZykeMessageTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MessagesViewModel by viewModels {
        val container = (application as ZykeMessagesApplication).container
        MessagesViewModel.Factory(container.observeMessages, container.userMessageState)
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory((application as ZykeMessagesApplication).container.observeMessages)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ZykeMessageTheme {
                AppRoot(viewModel, dashboardViewModel)
            }
        }
    }
}
