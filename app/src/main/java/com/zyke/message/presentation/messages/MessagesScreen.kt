package com.zyke.message.presentation.messages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
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
import com.zyke.message.presentation.messages.components.InboxModeBar
import com.zyke.message.presentation.messages.components.SendersList
import com.zyke.message.presentation.messages.components.SpendSummarySheet
import com.zyke.message.presentation.messages.components.ThreadSheet
import java.time.LocalDate
import java.time.ZoneId
import com.zyke.message.presentation.common.ErrorState
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.common.PermissionGate
import com.zyke.message.presentation.messages.components.ActiveFilterBar
import com.zyke.message.presentation.messages.components.MessageDetailSheet
import com.zyke.message.presentation.messages.components.MessagesFilterSheet
import com.zyke.message.presentation.messages.components.QuickFilterTabs
import com.zyke.message.presentation.messages.components.extrasSummary
import com.zyke.message.presentation.messages.components.FeedActions
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.messages.components.MessageList
import com.zyke.message.presentation.messages.components.MessageListSkeleton
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
        Column(Modifier.fillMaxSize()) {
            val ready = state.permission == PermissionStatus.GRANTED && !state.isLoading && !state.hasError
            if (ready) {
                InboxModeBar(
                    mode = state.viewMode,
                    onMode = { onIntent(Intent.SetViewMode(it)) }
                ) {
                    when {
                        state.viewMode == ViewMode.TIMELINE && !state.isSearching -> QuickFilterTabs(
                            feed = state.feed,
                            selected = state.filter.smart,
                            onSelect = { onIntent(Intent.SelectSmartFilter(it)) }
                        )
                        // Type tabs don't apply here, so say what is being shown instead.
                        state.viewMode == ViewMode.SENDERS -> ModeSummary(
                            DisplayFormatters.count(state.conversations.size, "sender", "senders")
                        )
                        else -> ModeSummary(DisplayFormatters.count(state.feed.visibleCount, "result", "results"))
                    }
                }
                if (state.viewMode == ViewMode.TIMELINE && !state.isSearching && state.filter.hasExtras) {
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

        state.thread?.let { thread ->
            ThreadSheet(
                thread = thread,
                state = state,
                actions = actions,
                onDismiss = { onIntent(Intent.CloseThread) }
            )
        }


        state.detail?.let { detail ->
            MessageDetailSheet(
                item = detail,
                isPinned = detail.id in state.userState.pinnedIds,
                isArchived = detail.id in state.userState.archivedIds,
                inConversation = state.thread != null,
                actions = actions,
                onMoreFromSender = { onIntent(Intent.OpenThreadFor(detail.id)) },
                onDismiss = { onIntent(Intent.CloseDetail) }
            )
        }

        val summary = state.feed.summary
        if (state.showSummarySheet && summary != null) {
            SpendSummarySheet(summary, onDismiss = { onIntent(Intent.CloseSummary) })
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
    onToggleGroup = { this(Intent.ToggleGroup(it)) },
    onCopyOtp = { id, code -> this(Intent.CopyOtp(id, code)) },
    onArchive = { this(Intent.Archive(it)) },
    onUnarchive = { this(Intent.Unarchive(it)) },
    onTogglePin = { this(Intent.TogglePin(it)) },
    onDismissAttention = { this(Intent.DismissAttention(it)) },
    onOpenSummary = { this(Intent.OpenSummary) },
    onOpenLink = { this(Intent.OpenLink(it)) }
)

/** Fills the tab area of the Inbox bar with a short count when there are no type tabs. */
@Composable
private fun ModeSummary(text: String) {
    Text(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1
    )
}

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
            Text(hint, style = MaterialTheme.typography.bodySmall, color = mutedColor(), textAlign = TextAlign.Center)
        }
    }
}
