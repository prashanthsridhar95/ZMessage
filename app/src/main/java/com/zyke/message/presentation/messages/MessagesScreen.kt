package com.zyke.message.presentation.messages

import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.presentation.tour.TourScroll
import com.zyke.message.presentation.tour.tourAnchor
import androidx.compose.foundation.clickable
import com.zyke.message.presentation.common.LocalOpenSettings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.zyke.message.presentation.common.Text
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import com.zyke.message.domain.feed.Cleanup
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.messages.components.Caption
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
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.motion.AppearVisibility
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.motion.rememberEnterGate
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TICK_MILLIS = 30_000L

/** Stateful entry point: wires the [MessagesViewModel] to the stateless [MessagesScreen]. */
@Composable
fun MessagesRoute(viewModel: MessagesViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = com.zyke.message.presentation.motion.rememberHaptics()
    val scope = rememberCoroutineScope()
    val snackbarHostState = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onIntent(Intent.PermissionResult(granted)) }

    val openLink = com.zyke.message.presentation.common.LocalOpenLink.current
    LaunchedEffect(viewModel) {
        // Demo mode reads no messages, so it needs no permission and never asks for it.
        val granted = com.zyke.message.data.demo.DemoMode.active || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
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
                    haptics.longPress()
                    Toast.makeText(context, R.string.otp_copied, Toast.LENGTH_SHORT).show()
                }
                is Effect.OpenLink -> openLink(effect.url)
                is Effect.ManyArchived -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "Archived ${effect.ids.size}",
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onIntent(Intent.UnarchiveMany(effect.ids))
                }
                is Effect.CategoryChanged -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "Filed ${effect.name} under ${effect.category?.displayString ?: "automatic"}",
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.onIntent(Intent.SetCategory(effect.messageId, effect.previous, announce = false))
                    }
                }
                is Effect.ReadChanged -> scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = if (effect.nowRead) "Marked as read" else "Marked as unread",
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onIntent(Intent.SetReadMany(effect.ids, !effect.nowRead))
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
    // Above the loading state, so the first rows enter once when the inbox appears and never again (rotation, tab changes).
    val enterGate = rememberEnterGate()

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val ready = state.permission == PermissionStatus.GRANTED && !state.isLoading && !state.hasError
            if (ready) {
                InboxModeBar(
                    mode = state.viewMode,
                    onMode = { onIntent(Intent.SetViewMode(it)) },
                    modifier = Modifier.tourAnchor(TourAnchor.MESSAGES_FEED)
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
                val staleIds = remember(state.messages, state.userState, state.filter.smart, state.now) {
                    Cleanup.staleIds(state.messages, state.userState, state.now, state.filter.smart)
                }
                // Each of these opens and closes lightly instead of popping in and pushing the list down.
                AppearVisibility(staleIds.isNotEmpty() && !state.isSearching && state.viewMode == ViewMode.TIMELINE) {
                    CleanupBar(
                        text = if (state.filter.smart == SmartFilter.PROMOS) {
                            "${staleIds.size} promotions older than 30 days"
                        } else {
                            "${staleIds.size} one-time codes older than a day"
                        },
                        onArchive = { onIntent(Intent.ArchiveMany(staleIds)) }
                    )
                }
                state.feed.searchNote?.takeIf { state.isSearching }?.let {
                    Caption("Showing: $it", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs), maxLines = 2)
                }
                AppearVisibility(state.viewMode == ViewMode.TIMELINE && !state.isSearching && state.filter.hasExtras) {
                    ActiveFilterBar(
                        summary = state.filter.extrasSummary(),
                        resultCount = state.feed.visibleCount,
                        onChange = { onIntent(Intent.OpenFilters) },
                        onClear = { onIntent(Intent.ResetExtras) }
                    )
                }
            }

            // Loading, the list, empty and error swap with a short crossfade (instant when motion is reduced); the
            // permission gate and the error appear at once because they are not content arriving.
            val body = when {
                state.permission == PermissionStatus.DENIED -> Body.PERMISSION
                state.hasError -> Body.ERROR
                state.isLoading || state.permission == PermissionStatus.UNKNOWN -> Body.LOADING
                state.viewMode == ViewMode.SENDERS -> if (state.conversations.isEmpty()) Body.EMPTY else Body.SENDERS
                state.feed.visibleCount == 0 -> Body.EMPTY
                else -> Body.LIST
            }
            CrossfadeContent(body, Modifier.fillMaxSize(), label = "messages-body") { shown ->
                Box(Modifier.fillMaxSize()) {
                    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
                    when (shown) {
                        Body.PERMISSION -> PermissionGate(onGrant = { onIntent(Intent.RequestPermission) })
                        Body.ERROR -> ErrorState(
                            title = stringResource(R.string.messages_error),
                            hint = stringResource(R.string.error_hint),
                            onRetry = { onIntent(Intent.Retry) }
                        )
                        Body.LOADING -> MessageListSkeleton()
                        Body.SENDERS -> SendersList(
                            conversations = state.conversations,
                            today = today,
                            onOpen = { onIntent(Intent.OpenSender(it)) },
                            modifier = Modifier.fillMaxSize()
                        )
                        Body.EMPTY -> MessagesEmpty(state, onIntent)
                        Body.LIST -> MessageList(state, actions, Modifier.fillMaxSize(), gate = enterGate)
                    }
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


        state.mergeRequest?.let { (primaryId, duplicateId) ->
            val primary = state.messages.firstOrNull { it.id == primaryId }
            val duplicate = state.messages.firstOrNull { it.id == duplicateId }
            if (primary != null && duplicate != null) {
                com.zyke.message.presentation.messages.components.MergeSheet(
                    primary, duplicate,
                    onSwap = { onIntent(Intent.SwapMerge) },
                    onConfirm = { onIntent(Intent.ConfirmMerge) },
                    onCancel = { onIntent(Intent.CancelMerge) }
                )
            }
        }

        state.detail?.let { detail ->
            MessageDetailSheet(
                item = detail,
                isPinned = detail.id in state.userState.pinnedIds,
                isArchived = detail.id in state.userState.archivedIds,
                inConversation = state.thread != null,
                actions = actions,
                onMoreFromSender = { onIntent(Intent.OpenThreadFor(detail.id)) },
                onDismiss = { onIntent(Intent.CloseDetail) },
                mergeCandidates = remember(detail.id, state.messages) { com.zyke.message.domain.merge.MergeCandidates.find(detail, state.messages) },
                related = remember(detail.id, state.messages) { com.zyke.message.domain.ledger.PaymentGroups.of(state.messages).followersOf(detail.id) }
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

    }
}

private fun ((Intent) -> Unit).toFeedActions() = FeedActions(
    onOpenMessage = { this(Intent.OpenMessage(it)) },
    onToggleGroup = { this(Intent.ToggleGroup(it)) },
    onCopyOtp = { id, code -> this(Intent.CopyOtp(id, code)) },
    onArchive = { this(Intent.Archive(it)) },
    onUnarchive = { this(Intent.Unarchive(it)) },
    onTogglePin = { this(Intent.TogglePin(it)) },
    onSetRead = { id, read -> this(Intent.SetRead(id, read)) },
    onDismissAttention = { this(Intent.DismissAttention(it)) },
    onMarkPaid = { this(Intent.DismissAttention(it, forever = true)) },
    onOpenSummary = { this(Intent.OpenSummary) },
    onOpenLink = { this(Intent.OpenLink(it)) },
    onSetCategory = { id, category -> this(Intent.SetCategory(id, category)) },
    onSetMessageCategory = { id, category -> this(Intent.SetMessageCategory(id, category)) },
    onToggleMute = { this(Intent.ToggleMute(it)) },
    onRequestMerge = { primary, duplicate -> this(Intent.RequestMerge(primary, duplicate)) },
    onUnmerge = { this(Intent.Unmerge(it)) },
    onResetFilters = { this(Intent.ResetFilters) }
)

/** What the area under the bars shows; a change between two of them is a crossfade. */
private enum class Body { PERMISSION, ERROR, LOADING, SENDERS, EMPTY, LIST }

/** Offers to tidy away old promotions or codes in one tap; the snackbar that follows can undo it. */
@Composable
private fun CleanupBar(text: String, onArchive: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Caption(text, Modifier.weight(1f), maxLines = 2)
        AppButton("Archive", onArchive, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
    }
}

/** Fills the tab area of the Inbox bar with a short count when there are no type tabs. */
@Composable
private fun ModeSummary(text: String) {
    Text(
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1
    )
}

/**
 * Nothing to list, said once in the shared empty state's voice: a friendly line for why, and when there is something to do
 * about it, one action (clear the search, clear the filters).
 */
@Composable
private fun MessagesEmpty(state: State, onIntent: (Intent) -> Unit) {
    val (title, body, action) = when {
        state.isSearching && state.query.isNotBlank() -> Triple(
            stringResource(R.string.search_empty), stringResource(R.string.search_empty_hint),
            "Clear search" to { onIntent(Intent.SetQuery("")) }
        )
        state.viewMode == ViewMode.TIMELINE && state.filter.isActive -> Triple(
            stringResource(R.string.filter_empty), stringResource(R.string.filter_empty_hint),
            "Show everything" to { onIntent(Intent.ResetFilters) }
        )
        else -> Triple(stringResource(R.string.messages_empty), "New messages show up here as they arrive.", null)
    }
    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        EmptyState(
            title = title, body = body, icon = R.drawable.baseline_message_24,
            actionLabel = action?.first, onAction = action?.second
        )
    }
}
