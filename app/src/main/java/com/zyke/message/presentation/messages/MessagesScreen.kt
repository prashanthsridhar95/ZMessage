package com.zyke.message.presentation.messages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.presentation.messages.MessagesContract.Effect
import com.zyke.message.presentation.messages.MessagesContract.Intent
import com.zyke.message.presentation.messages.MessagesContract.PermissionStatus
import com.zyke.message.presentation.messages.MessagesContract.State
import com.zyke.message.presentation.messages.components.MessageList
import com.zyke.message.presentation.messages.components.MessagesBottomBar
import com.zyke.message.presentation.messages.components.MessagesTopBar

/** Stateful entry point: wires the [MessagesViewModel] to the stateless [MessagesScreen]. */
@Composable
fun MessagesRoute(viewModel: MessagesViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onIntent(Intent.PermissionResult(granted)) }

    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                Effect.LaunchPermissionRequest -> permissionLauncher.launch(Manifest.permission.READ_SMS)
                is Effect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    Toast.makeText(context, R.string.otp_copied, Toast.LENGTH_SHORT).show()
                }
                is Effect.ShowMessage -> Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    MessagesScreen(state = state, onIntent = viewModel::onIntent)
}

@Composable
fun MessagesScreen(state: State, onIntent: (Intent) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MessagesTopBar(
                filtersVisible = state.showFilters,
                onToggleFilters = { onIntent(Intent.ToggleFilters) }
            )
        },
        bottomBar = { MessagesBottomBar() }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.permission == PermissionStatus.DENIED -> PermissionRequired(
                    onGrant = { onIntent(Intent.RequestPermission) }
                )
                state.hasError -> CenteredMessage(stringResource(R.string.messages_error))
                state.isLoading || state.permission == PermissionStatus.UNKNOWN -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                state.messages.isEmpty() -> CenteredMessage(stringResource(R.string.messages_empty))
                else -> MessageList(
                    sections = state.sections,
                    expandedIds = state.expandedIds,
                    showFilters = state.showFilters,
                    selectedFilter = state.selectedFilter,
                    onSelectFilter = { onIntent(Intent.SelectFilter(it)) },
                    onToggleExpanded = { onIntent(Intent.ToggleExpanded(it)) },
                    onCopyOtp = { onIntent(Intent.CopyOtp(it)) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun PermissionRequired(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.permission_rationale),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.secondary
        )
        Button(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.secondary)
    }
}
