package com.zyke.message.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.presentation.messages.MessagesRoute
import com.zyke.message.presentation.messages.MessagesViewModel
import com.zyke.message.presentation.theme.ZykeMessageTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MessagesViewModel by viewModels {
        val container = (application as ZykeMessagesApplication).container
        MessagesViewModel.Factory(container.observeMessages, container.userMessageState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ZykeMessageTheme {
                MessagesRoute(viewModel)
            }
        }
    }
}
