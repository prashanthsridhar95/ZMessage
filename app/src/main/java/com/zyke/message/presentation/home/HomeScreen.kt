package com.zyke.message.presentation.home

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.domain.dashboard.CategoryTotal
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.AppTab
import com.zyke.message.presentation.common.ErrorState
import com.zyke.message.presentation.common.PermissionGate
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.dashboard.components.HeroCard
import com.zyke.message.presentation.dashboard.components.SectionCard
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
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        when {
            state.permissionMissing -> PermissionGate(onGrant = onRequestPermission)
            state.hasError -> ErrorState(
                title = "Couldn't read your messages",
                hint = "Check that SMS access is allowed, then try again.",
                onRetry = { onIntent(Intent.Retry) }
            )
            state.isLoading || !state.hasLoaded -> MessageListSkeleton()
            else -> HomeContent(state, onIntent, onNavigate)
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
            onOpenLink = { onIntent(Intent.OpenLink(it)) }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.isBlank) {
            item(key = "blank") {
                SectionCard("Nothing here yet") {
                    Caption("Payments, bills and OTPs show up here as soon as the bank messages arrive.", maxLines = 3)
                }
            }
        }

        if (state.attention.isNotEmpty()) {
            item(key = "attention") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Needs your attention", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    AttentionStrip(state.attention, today, actions)
                }
            }
        }

        state.thisMonth?.takeIf { !it.isEmpty }?.let { month ->
            item(key = "month") {
                Column {
                    HeroCard(month, DashboardPeriod.THIS_MONTH, onDirection = { onNavigate(AppTab.MONEY) }, showSelection = false)
                    LinkButton("See where it went", { onNavigate(AppTab.MONEY) })
                }
            }
        }

        if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming") {
                SectionCard("Coming up", subtitle = "Next 30 days") {
                    state.upcoming.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        UpcomingRow(item, today) { onIntent(Intent.OpenLink(it)) }
                    }
                }
            }
        }

        if (state.budgetStatus.isNotEmpty()) {
            item(key = "budgets") {
                SectionCard("Budgets", subtitle = "This month") {
                    state.budgetStatus.take(3).forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        BudgetLine(item)
                    }
                    LinkButton("Manage budgets", { onNavigate(AppTab.MONEY) })
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "recent") {
                SectionCard("Recent payments") {
                    state.recent.forEachIndexed { index, message ->
                        if (index > 0) HorizontalDivider()
                        RecentRow(message)
                    }
                    LinkButton("Open inbox", { onNavigate(AppTab.INBOX) })
                }
            }
        }

        if (state.unreadCount > 0) {
            item(key = "unread") {
                SectionCard("Inbox") {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Open inbox") { onNavigate(AppTab.INBOX) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${state.unreadCount} unread ${if (state.unreadCount == 1) "message" else "messages"}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text("→", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(item: UpcomingItem, today: LocalDate, onPay: (String) -> Unit) {
    val urgent = DisplayFormatters.isUrgent(item.date, today)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun BudgetLine(item: CategoryTotal) {
    val budget = item.budget ?: return
    val used = item.usedFraction ?: 0.0
    val over = used > 1.0
    val color = when {
        over -> MaterialTheme.colorScheme.error
        used >= 0.8 -> ChartColors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val remaining = item.remaining ?: budget

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
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
private fun RecentRow(message: ClassifiedMessage) {
    val tx = message.content as? MessageContent.Transaction ?: return
    val sign = if (tx.isReceived) "+" else "-"
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
