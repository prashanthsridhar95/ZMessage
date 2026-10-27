package com.zyke.message.presentation.home

import com.zyke.message.presentation.common.heading
import com.zyke.message.domain.insights.SpendNarrative
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LinkButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import com.zyke.message.presentation.common.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.domain.dashboard.CategoryTotal
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.AppTab
import com.zyke.message.presentation.common.ErrorState
import com.zyke.message.presentation.common.rememberAnimatedRupees
import com.zyke.message.presentation.common.PermissionGate
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import com.zyke.message.presentation.home.HomeContract.Effect
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.AttentionStrip
import com.zyke.message.presentation.messages.components.AmountText
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.FeedActions
import com.zyke.message.presentation.messages.components.MessageDetailSheet
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onNavigate: (AppTab) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is Effect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    Toast.makeText(context, R.string.otp_copied, Toast.LENGTH_SHORT).show()
                }
                is Effect.OpenLink -> runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(effect.url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Toast.makeText(context, R.string.link_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    HomeScreen(state, viewModel::onIntent, onNavigate, onRequestPermission, modifier)
}

@Composable
fun HomeScreen(
    state: State,
    onIntent: (Intent) -> Unit,
    onNavigate: (AppTab) -> Unit,
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        val stage = when {
            state.permissionMissing -> 0
            state.hasError -> 1
            state.isLoading || !state.hasLoaded -> 2
            else -> 3
        }
        // The skeleton fades into the real content instead of being swapped for it.
        Crossfade(stage, animationSpec = tween(Motion.STANDARD_MS), label = "home-stage") { shown ->
            when (shown) {
                0 -> PermissionGate(onGrant = onRequestPermission)
                1 -> ErrorState(
                    title = "Couldn't read your messages",
                    hint = "Check that SMS access is allowed, then try again.",
                    onRetry = { onIntent(Intent.Retry) }
                )
                2 -> MessageListSkeleton()
                else -> HomeContent(state, onIntent, onNavigate)
            }
        }
    }
}

@Composable
private fun HomeContent(state: State, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit) {
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val actions = remember(onIntent) {
        FeedActions.None.copy(
            onCopyOtp = { id, code -> onIntent(Intent.CopyOtp(id, code)) },
            onDismissAttention = { onIntent(Intent.DismissAttention(it)) },
            onOpenLink = { onIntent(Intent.OpenLink(it)) },
            onOpenMessage = { onIntent(Intent.OpenMessage(it)) },
            onTogglePin = { onIntent(Intent.TogglePin(it)) },
            onArchive = { onIntent(Intent.Archive(it)) },
            onUnarchive = { onIntent(Intent.Unarchive(it)) },
            onSetCategory = { id, category -> onIntent(Intent.SetCategory(id, category)) }
        )
    }

    // Anything already shown under "Needs your attention" is not repeated under "Coming up".
    val attentionIds = state.attention.mapTo(HashSet()) { it.message.id }
    val upcoming = state.upcoming.filter { it.message.id !in attentionIds }
    // Budgets only speak up when one is getting close; the rest are a tap away in Money.
    val budgets = state.budgetStatus.filter { (it.usedFraction ?: 0.0) >= BUDGET_WARNING }.take(MAX_BUDGETS)

    HomeSheets(state, actions, onIntent, onNavigate)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        if (state.isBlank) {
            item(key = "blank") {
                PlainSection("Nothing here yet") {
                    Caption("Payments, bills and OTPs show up here as soon as the bank messages arrive.", maxLines = 3)
                }
            }
        }

        state.thisMonth?.takeIf { !it.isEmpty }?.let { month ->
            item(key = "month") { MonthHeadline(month) { onNavigate(AppTab.MONEY) } }
        }

        if (state.attention.isNotEmpty()) {
            item(key = "attention") {
                AttentionStrip(state.attention, today, actions)
            }
        }

        if (state.alerts.isNotEmpty()) {
            item(key = "alerts") {
                PlainSection("Worth a look", subtitle = "Found on your phone from your own history") {
                    state.alerts.forEachIndexed { index, alert ->
                        if (index > 0) HorizontalDivider()
                        Text(
                            alert.text,
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "Open details") { onIntent(Intent.OpenMessage(alert.message.id)) }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            item(key = "upcoming") {
                PlainSection("Coming up", subtitle = "Next 30 days") {
                    upcoming.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        UpcomingRow(item, today, onOpen = { onIntent(Intent.OpenMessage(item.message.id)) }) { onIntent(Intent.OpenLink(it)) }
                    }
                }
            }
        }

        if (budgets.isNotEmpty()) {
            item(key = "budgets") {
                PlainSection("Budgets to watch", subtitle = "This month") {
                    budgets.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        BudgetLine(item, onOpen = { onIntent(Intent.OpenBudget(item.category)) })
                    }
                    LinkButton("All budgets", { onNavigate(AppTab.MONEY) })
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "recent") {
                PlainSection("Recent payments") {
                    state.recent.take(MAX_RECENT).forEachIndexed { index, message ->
                        if (index > 0) HorizontalDivider()
                        RecentRow(message, onOpen = { onIntent(Intent.OpenMessage(message.id)) })
                    }
                    LinkButton(
                        text = if (state.unreadCount > 0) "Open inbox · ${state.unreadCount} unread" else "Open inbox",
                        onClick = { onNavigate(AppTab.INBOX) }
                    )
                }
            }
        }
    }
}

/** Opened by tapping any item: the message in full, or a budget's detail. */
@Composable
private fun HomeSheets(state: State, actions: FeedActions, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit) {
    state.detail?.let { detail ->
        MessageDetailSheet(
            item = detail,
            isPinned = detail.id in state.userState.pinnedIds,
            isArchived = detail.id in state.userState.archivedIds,
            inConversation = false,
            actions = actions,
            onMoreFromSender = {
                onIntent(Intent.CloseMessage)
                onNavigate(AppTab.INBOX)
            },
            onDismiss = { onIntent(Intent.CloseMessage) }
        )
    }

    state.budgetDetail?.let { category ->
        val item = state.budgetStatus.firstOrNull { it.category == category }
        if (item != null) {
            BudgetSheet(
                item = item,
                onOpenMoney = {
                    onIntent(Intent.CloseBudget)
                    onNavigate(AppTab.MONEY)
                },
                onDismiss = { onIntent(Intent.CloseBudget) }
            )
        }
    }
}

@Composable
private fun BudgetSheet(item: CategoryTotal, onOpenMoney: () -> Unit, onDismiss: () -> Unit) {
    val budget = item.budget ?: return
    val used = item.usedFraction ?: 0.0
    val over = used > 1.0
    val remaining = item.remaining ?: budget

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(item.category.displayString, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Caption("Monthly budget")
            Text(
                "${DisplayFormatters.rupees(item.total)} of ${DisplayFormatters.rupees(budget)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            ShareBar(
                used.toFloat(),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Caption(
                (if (over) "${DisplayFormatters.rupees(remaining.negate())} over budget" else "${DisplayFormatters.rupees(remaining)} left") +
                    " · ${DisplayFormatters.count(item.count, "payment", "payments")} this month",
                color = if (over) MaterialTheme.colorScheme.error else mutedColor()
            )
            AppButton("See where it went", onOpenMoney, Modifier.fillMaxWidth())
        }
    }
}

private const val BUDGET_WARNING = 0.8
private const val MAX_BUDGETS = 3
private const val MAX_RECENT = 3

/** A heading and its rows on the plain page: no box, no fill. */
@Composable
private fun PlainSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, Modifier.heading(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) Caption(subtitle, Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** The month in one line of type: what was spent, and how that compares. Tap for the detail. */
@Composable
private fun MonthHeadline(month: Dashboard, onOpen: () -> Unit) {
    val change = month.changePercent
    val note = change?.let {
        val percent = abs(it).roundToInt()
        if (percent == 0) "About the same as the same days last month"
        else "$percent% ${if (it >= 0) "more" else "less"} than the same days last month"
    }
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "See where it went", onClick = onOpen)) {
        Caption("Spent in ${DisplayFormatters.monthName(java.time.YearMonth.from(month.range.start))}")
        Text(
            text = rememberAnimatedRupees(month.total),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Caption(
            listOfNotNull(note, "Received ${DisplayFormatters.rupees(month.receivedTotal)}").joinToString(" · "),
            Modifier.padding(top = 2.dp),
            maxLines = 2
        )
        SpendNarrative.of(month)?.let { Caption(it, Modifier.padding(top = 2.dp), maxLines = 3) }
    }
}

@Composable
private fun UpcomingRow(item: UpcomingItem, today: LocalDate, onOpen: () -> Unit, onPay: (String) -> Unit) {
    val urgent = DisplayFormatters.isUrgent(item.date, today)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantChip(item.message.merchant.logo, description = item.title, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(item.title)
            Caption(
                (if (item.kind == UpcomingKind.CARD_BILL) "Bill · " else "Auto-debit · ") + DisplayFormatters.dueLabel(item.date, today),
                color = if (urgent) MaterialTheme.colorScheme.error else mutedColor()
            )
            val link = item.paymentLink
            if (link != null) {
                AppButton(
                    text = "Pay bill",
                    onClick = { onPay(link) },
                    style = ButtonStyle.TONAL,
                    size = ButtonSize.COMPACT,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        AmountText(DisplayFormatters.rupees(item.amount), MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun BudgetLine(item: CategoryTotal, onOpen: () -> Unit) {
    val budget = item.budget ?: return
    val used = item.usedFraction ?: 0.0
    val over = used > 1.0
    val color = when {
        over -> MaterialTheme.colorScheme.error
        used >= 0.8 -> ChartColors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val remaining = item.remaining ?: budget

    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowTitle(item.category.displayString, Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
            Text(
                "${DisplayFormatters.rupees(item.total)} of ${DisplayFormatters.rupees(budget)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        ShareBar(used.toFloat(), color = color)
        Caption(
            if (over) "${DisplayFormatters.rupees(remaining.negate())} over budget" else "${DisplayFormatters.rupees(remaining)} left",
            Modifier.padding(top = 4.dp),
            color = if (over) MaterialTheme.colorScheme.error else mutedColor()
        )
    }
}

@Composable
private fun RecentRow(message: ClassifiedMessage, onOpen: () -> Unit) {
    val tx = message.content as? MessageContent.Transaction ?: return
    val sign = if (tx.isReceived) "+" else "-"
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantChip(message.merchant.logo, description = message.merchant.name, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(message.merchant.displayName)
            Caption(
                DisplayFormatters.listStamp(message.message.date) +
                    (tx.accountNumber.takeIf { it.isNotEmpty() }?.let { " · •• $it" } ?: "")
            )
        }
        Spacer(Modifier.width(12.dp))
        AmountText(
            "$sign ${DisplayFormatters.rupees(tx.amount)}",
            if (tx.isReceived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
    }
}
