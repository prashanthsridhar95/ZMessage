package com.zyke.message.presentation.messages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.presentation.messages.MessagesContract.Effect
import com.zyke.message.presentation.messages.MessagesContract.Intent
import com.zyke.message.presentation.messages.MessagesContract.PermissionStatus
import com.zyke.message.presentation.messages.MessagesContract.State
import com.zyke.message.presentation.messages.MessagesContract.ViewMode
import com.zyke.message.presentation.messages.components.SegmentedControl
import com.zyke.message.presentation.messages.components.SendersList
import com.zyke.message.presentation.messages.components.ThreadScreen
import java.time.LocalDate
import java.time.ZoneId
import com.zyke.message.presentation.common.ErrorState
import com.zyke.message.presentation.common.PermissionGate
import com.zyke.message.presentation.messages.components.ActiveFilterBar
import com.zyke.message.presentation.messages.components.MessagesFilterSheet
import com.zyke.message.presentation.messages.components.QuickFilterTabs
import com.zyke.message.presentation.messages.components.extrasSummary
import com.zyke.message.presentation.messages.components.FeedActions
import com.zyke.message.presentation.messages.components.MessageList
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import com.zyke.message.presentation.messages.components.MessagesTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TICK_MILLIS = 30_000L

/** Stateful entry point: wires the [MessagesViewModel] to the stateless [MessagesScreen]. */
@Composable
fun MessagesRoute(viewModel: MessagesViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onIntent(Intent.PermissionResult(granted)) }

    // Back closes an open conversation before leaving the app.
    BackHandler(enabled = state.thread != null) { viewModel.onIntent(Intent.CloseThread) }

    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    // Keeps OTP expiry and due-date wording current while the screen is open.
    LaunchedEffect(viewModel) {
        while (true) {
            delay(TICK_MILLIS)
            viewModel.onIntent(Intent.Tick)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                Effect.LaunchPermissionRequest -> permissionLauncher.launch(Manifest.permission.READ_SMS)
                is Effect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    Toast.makeText(context, R.string.otp_copied, Toast.LENGTH_SHORT).show()
                }
                is Effect.OpenLink -> runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(effect.url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    Toast.makeText(context, R.string.link_failed, Toast.LENGTH_SHORT).show()
                }
                is Effect.MessageArchived -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.archived),
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.onIntent(Intent.Unarchive(effect.messageId))
                    }
                }
            }
        }
    }

    MessagesScreen(
        state = state,
        onIntent = viewModel::onIntent,
        snackbarHostState = snackbarHostState,
        modifier = modifier
    )
}

@Composable
fun MessagesScreen(
    state: State,
    onIntent: (Intent) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier
) {
    val actions = remember(onIntent) { onIntent.toFeedActions() }

    Box(modifier.fillMaxSize()) {
        val thread = state.thread
        if (thread != null) {
            ThreadScreen(
                thread = thread,
                state = state,
                actions = actions,
                onBack = { onIntent(Intent.CloseThread) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                MessagesTopBar(
                    isSearching = state.isSearching,
                    query = state.query,
                    onQueryChange = { onIntent(Intent.SetQuery(it)) },
                    onToggleSearch = { onIntent(Intent.ToggleSearch) },
                    activeFilterCount = state.filter.extraCount,
                    onOpenFilters = { onIntent(Intent.OpenFilters) }
                )

                val ready = state.permission == PermissionStatus.GRANTED && !state.isLoading && !state.hasError
                if (ready) {
                    SegmentedControl(
                        options = ViewMode.entries.map { it.label },
                        selectedIndex = state.viewMode.ordinal,
                        onSelect = { onIntent(Intent.SetViewMode(ViewMode.entries[it])) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (ready && !state.isSearching && state.viewMode == ViewMode.TIMELINE) {
                    QuickFilterTabs(
                        feed = state.feed,
                        selected = state.filter.smart,
                        onSelect = { onIntent(Intent.SelectSmartFilter(it)) }
                    )
                    if (state.filter.hasExtras) {
                        ActiveFilterBar(
                            summary = state.filter.extrasSummary(),
                            resultCount = state.feed.visibleCount,
                            onChange = { onIntent(Intent.OpenFilters) },
                            onClear = { onIntent(Intent.ResetExtras) }
                        )
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
                    when {
                        state.permission == PermissionStatus.DENIED -> PermissionGate(
                            onGrant = { onIntent(Intent.RequestPermission) }
                        )
                        state.hasError -> ErrorState(
                            title = stringResource(R.string.messages_error),
                            hint = stringResource(R.string.error_hint),
                            onRetry = { onIntent(Intent.Retry) }
                        )
                        state.isLoading || state.permission == PermissionStatus.UNKNOWN -> MessageListSkeleton()
                        state.viewMode == ViewMode.SENDERS -> if (state.conversations.isEmpty()) {
                            EmptyState(state)
                        } else {
                            SendersList(
                                conversations = state.conversations,
                                today = today,
                                onOpen = { onIntent(Intent.OpenSender(it)) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        state.feed.visibleCount == 0 -> EmptyState(state)
                        else -> MessageList(state, actions, Modifier.fillMaxSize())
                    }
                }
            }
        }

        if (state.showFilterSheet) {
            MessagesFilterSheet(
                filter = state.filter,
                feed = state.feed,
                onSelectCategory = { onIntent(Intent.SelectCategory(it)) },
                onSelectStatus = { onIntent(Intent.SelectStatus(it)) },
                onSelectWindow = { onIntent(Intent.SelectWindow(it)) },
                onReset = { onIntent(Intent.ResetExtras) },
                onDismiss = { onIntent(Intent.CloseFilters) }
            )
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

private fun ((Intent) -> Unit).toFeedActions() = FeedActions(
    onOpenMessage = { this(Intent.OpenMessage(it)) },
    onToggleExpanded = { this(Intent.ToggleExpanded(it)) },
    onToggleGroup = { this(Intent.ToggleGroup(it)) },
    onCopyOtp = { id, code -> this(Intent.CopyOtp(id, code)) },
    onArchive = { this(Intent.Archive(it)) },
    onUnarchive = { this(Intent.Unarchive(it)) },
    onTogglePin = { this(Intent.TogglePin(it)) },
    onDismissAttention = { this(Intent.DismissAttention(it)) },
    onToggleSummary = { this(Intent.ToggleSummary) },
    onOpenLink = { this(Intent.OpenLink(it)) }
)

@Composable
private fun EmptyState(state: State) {
    when {
        state.isSearching && state.query.isNotBlank() ->
            CenteredMessage(stringResource(R.string.search_empty), stringResource(R.string.search_empty_hint))
        state.viewMode == ViewMode.TIMELINE && state.filter.isActive ->
            CenteredMessage(stringResource(R.string.filter_empty), stringResource(R.string.filter_empty_hint))
        else -> CenteredMessage(stringResource(R.string.messages_empty), null)
    }
}

@Composable
private fun CenteredMessage(title: String, hint: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        }
    }
}
