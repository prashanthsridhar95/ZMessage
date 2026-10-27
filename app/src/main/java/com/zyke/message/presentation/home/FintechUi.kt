package com.zyke.message.presentation.home

import com.zyke.message.presentation.common.FintechCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.accounts.AccountsOverview
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LocalContentInset
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.rememberAnimatedRupees
import com.zyke.message.presentation.dashboard.components.LineTrend
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the accounts hold in all, as the one big number on Home. */
@Composable
internal fun BalanceHero(overview: AccountsOverview, onOpen: () -> Unit) {
    FintechCard(onClick = onOpen) {
        Caption("TOTAL BALANCE")
        val total = overview.totalBalance
        if (total != null) {
            Text(
                rememberAnimatedRupees(total),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Caption(
                "across ${DisplayFormatters.count(overview.countedBalances, "bank account", "bank accounts")} · from their latest messages",
                Modifier.padding(top = 2.dp),
                maxLines = 2
            )
        } else {
            Text("Your accounts", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Caption("Balances show up once your bank's messages mention them", Modifier.padding(top = 2.dp), maxLines = 2)
        }
    }
}

/** A running clock for screens that count down, ticking once a second. */
@Composable
internal fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    return now
}

// ---- accounts

@Composable
internal fun AccountsPage(state: HomeContract.State, onIntent: (HomeContract.Intent) -> Unit) {
    val overview = state.accounts
    val banks = overview.accounts.filter { !it.isCard }
    val cards = overview.accounts.filter { it.isCard }
    val byId = remember(state.messages) { state.messages.associateBy { it.id } }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "total") { BalanceHero(overview, onOpen = {}) }

        if (overview.accounts.isEmpty()) {
            item(key = "empty") {
                FintechCard {
                    RowTitle("No accounts yet")
                    Caption("Bank accounts and cards appear here as soon as their messages arrive.", maxLines = 3)
                }
            }
        }
        if (banks.isNotEmpty()) {
            item(key = "banks-title") { SectionLabel("Bank accounts") }
            banks.forEach { account ->
                item(key = account.key) { AccountTile(account, byId[account.sampleMessageId]) { onIntent(HomeContract.Intent.OpenAccount(account.key)) } }
            }
        }
        if (cards.isNotEmpty()) {
            item(key = "cards-title") { SectionLabel("Cards") }
            cards.forEach { account ->
                item(key = account.key) { AccountTile(account, byId[account.sampleMessageId]) { onIntent(HomeContract.Intent.OpenAccount(account.key)) } }
            }
        }
        item(key = "goals") {
            GoalsCard(
                goals = state.goals,
                onSave = { onIntent(HomeContract.Intent.SaveGoal(it)) },
                onRemove = { onIntent(HomeContract.Intent.RemoveGoal(it)) },
                onAdd = { id, amount -> onIntent(HomeContract.Intent.AddToGoal(id, amount)) }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 4.dp, top = 12.dp).heading(),
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun AccountTile(account: AccountSummary, sample: ClassifiedMessage?, onOpen: () -> Unit) {
    val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val due = account.dueDate
    val asOf = account.balanceAsOf
    FintechCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sample != null) {
                MerchantChip(sample.merchant.logo, imageKey = sample.merchant.imageKey, name = sample.merchant.name, description = account.title, size = 44.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                RowTitle(account.title)
                Caption("${account.kind.label} •• ${account.last4}")
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                val headline = if (account.isCard) account.dueAmount else account.balance
                Text(
                    headline?.let { DisplayFormatters.rupees(it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Caption(
                    when {
                        account.isCard && due != null -> "due ${dayFormat.format(due)}"
                        account.isCard -> "latest bill"
                        asOf != null -> "as of ${dayFormat.format(asOf)}"
                        else -> "balance not stated"
                    }
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Spent", DisplayFormatters.rupees(account.spent))
            Stat("Received", DisplayFormatters.rupees(account.received))
            Stat("Payments", account.activityCount.toString())
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Caption(label.uppercase())
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** One account in full: what it holds, how that has moved, this cycle's spending, the bill for a card, and its payments. */
@Composable
internal fun AccountSheet(
    account: AccountSummary,
    state: HomeContract.State,
    onPay: (String) -> Unit,
    onName: (String) -> Unit,
    onOpenMessage: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val history = remember(state.messages, account.last4) { BalanceReader.history(state.messages, zone)[account.last4].orEmpty() }
    val activity = remember(state.messages, account.key) {
        state.messages.filter {
            val tx = it.content as? MessageContent.Transaction ?: return@filter false
            tx.accountNumber.filter(Char::isDigit).takeLast(4) == account.last4 && tx.isCard == account.isCard
        }.take(ACTIVITY_SHOWN)
    }
    val sample = state.messages.firstOrNull { it.id == account.sampleMessageId }
    val due = account.dueDate
    val asOf = account.balanceAsOf
    var naming by rememberSaveable { mutableStateOf(false) }

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sample != null) {
                    MerchantChip(sample.merchant.logo, imageKey = sample.merchant.imageKey, name = sample.merchant.name, description = account.title, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(account.title, Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Caption("${account.kind.label} •• ${account.last4}")
                }
                AppButton("Rename", { naming = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }

            Column {
                Caption(if (account.isCard) "LATEST BILL" else "BALANCE")
                Text(
                    (if (account.isCard) account.dueAmount else account.balance)?.let { DisplayFormatters.rupees(it) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Caption(
                    when {
                        account.isCard && due != null ->
                            "due ${dayFormat.format(due)}" + (account.minimumDue?.let { " · minimum ${DisplayFormatters.rupees(it)}" } ?: "")
                        asOf != null -> "as of ${dayFormat.format(asOf)}, from its latest message"
                        else -> "Its messages do not state a balance"
                    },
                    maxLines = 2
                )
            }

            account.paymentLink?.let { AppButton("Pay bill", { onPay(it) }, Modifier.fillMaxWidth()) }

            if (history.size >= 2) {
                Column {
                    RowTitle("Balance over time")
                    LineTrend(
                        amounts = history.map { it.amount },
                        firstLabel = dayFormat.format(history.first().asOf),
                        lastLabel = dayFormat.format(history.last().asOf),
                        description = "Line chart of the balance, from ${DisplayFormatters.rupees(history.first().amount)} to ${DisplayFormatters.rupees(history.last().amount)}",
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            FintechCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Spent", DisplayFormatters.rupees(account.spent))
                    Stat("Received", DisplayFormatters.rupees(account.received))
                    Stat("Payments", account.activityCount.toString())
                }
                Caption("This pay cycle", Modifier.padding(top = 10.dp))
            }

            if (activity.isNotEmpty()) {
                Column {
                    RowTitle("Recent activity")
                    activity.forEachIndexed { index, message ->
                        if (index > 0) HorizontalDivider()
                        RecentRow(message, onOpen = { onOpenMessage(message.id) })
                    }
                }
            }
        }
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf(account.userCard?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    Caption("For example \"Salary account\" or \"Travel card\". Kept on this phone only.", maxLines = 2)
                }
            },
            confirmButton = {
                AppButton("Save", { onName(name); naming = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = name.isNotBlank())
            },
            dismissButton = { AppButton("Cancel", { naming = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

private const val ACTIVITY_SHOWN = 6

// ---- codes

/** Fresh one-time codes with their countdowns; opened from the code card on Home. */
@Composable
internal fun CodesSheet(state: HomeContract.State, onCopy: (Long, String) -> Unit, onDismiss: () -> Unit) {
    val now = rememberNowMillis()
    val codes = state.messages.filter {
        it.content is MessageContent.Otp && it.message.date >= now - CODE_LIST_WINDOW_MS && it.id !in state.userState.archivedIds
    }.take(CODES_SHOWN)

    AppBottomSheet(onDismiss, contentInset = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "Codes", Modifier.padding(horizontal = 20.dp).heading(),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary
            )
            if (codes.isEmpty()) {
                Caption("A code shows up here the moment it arrives.", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), maxLines = 2)
            }
            codes.forEach { message ->
                val otp = message.content as MessageContent.Otp
                val left = (message.message.date + CODE_LIFE_WINDOW_MS - now).coerceAtLeast(0)
                CodeRow(
                    sender = otp.senderName.ifEmpty { message.merchant.name },
                    code = otp.code,
                    note = otp.paymentAmount?.let { "Payment of ${DisplayFormatters.rupees(it)}" },
                    fractionLeft = left.toFloat() / CODE_LIFE_WINDOW_MS,
                    secondsLeft = (left / 1000).toInt(),
                    onCopy = { onCopy(message.id, otp.code) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
        }
    }
}

/** The newest code that has not yet run out, or null. */
internal fun freshCode(state: HomeContract.State, nowMillis: Long): ClassifiedMessage? =
    state.messages.firstOrNull {
        it.content is MessageContent.Otp && nowMillis - it.message.date in 0 until CODE_LIFE_WINDOW_MS && it.id !in state.userState.archivedIds
    }

internal const val CODE_LIFE_WINDOW_MS = 10 * 60 * 1000L
private const val CODE_LIST_WINDOW_MS = 60 * 60 * 1000L
private const val CODES_SHOWN = 5
