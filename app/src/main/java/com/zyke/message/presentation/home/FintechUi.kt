package com.zyke.message.presentation.home

import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.presentation.common.UnderlineTabs
import kotlinx.coroutines.launch
import com.zyke.message.presentation.common.LocalImageStore
import com.zyke.message.domain.classification.MerchantResolver
import androidx.compose.runtime.collectAsState
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
                DisplayFormatters.count(overview.countedBalances, "bank account", "bank accounts"),
                Modifier.padding(top = 2.dp),
                maxLines = 2
            )
        } else {
            Text("Your accounts", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Caption("No balances yet", Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * A running clock for screens that count down. It ticks once a second while the app is on screen, stops while it is
 * not, and reads the real time the instant the app comes back, so a countdown never shows the moment it was left at.
 * What the countdown is measured against is always the clock on the phone, so time passes whether or not it is drawn.
 */
@Composable
internal fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                // Wake on the next second boundary, so the display changes exactly as the second does.
                kotlinx.coroutines.delay(1_000L - now % 1_000L)
            }
        }
    }
    return now
}

// ---- accounts

@Composable
internal fun AccountsPage(state: HomeContract.State, onIntent: (HomeContract.Intent) -> Unit) {
    val overview = state.accounts
    val banks = overview.accounts.filter { !it.isCard && !it.isProvidentFund }
    val funds = overview.accounts.filter { it.isProvidentFund }
    val cards = overview.accounts.filter { it.isCard }
    val byId = remember(state.messages) { state.messages.associateBy { it.id } }
    var tab by rememberSaveable { mutableStateOf(0) }
    val shown = when (tab) {
        0 -> banks
        1 -> cards
        else -> emptyList()
    }
    val pf = state.providentFund

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "total") { BalanceHero(overview, onOpen = {}) }

        item(key = "tabs") {
            UnderlineTabs(
                labels = listOf("Accounts", "Cards", "Savings"),
                counts = listOf(banks.size, cards.size, if (funds.isNotEmpty()) funds.size else if (pf != null) 1 else 0),
                selectedIndex = tab,
                onSelect = { tab = it },
                showDivider = false
            )
        }

        if (tab == 2) {
            if (pf == null && funds.isEmpty()) {
                item(key = "pf-empty") {
                    FintechCard {
                        Caption("No provident fund yet. Passbook messages from EPFO show up here, and you can mark any account as a provident fund from its sheet.", maxLines = 4)
                    }
                }
            }
            if (pf != null && funds.isEmpty()) item(key = "pf") { PfTile(pf) { onIntent(HomeContract.Intent.OpenMessage(pf.latest.messageId)) } }
            funds.forEach { fund ->
                item(key = fund.key) { FundTile(fund, pf) { onIntent(HomeContract.Intent.OpenAccount(fund.key)) } }
            }
        } else if (shown.isEmpty()) {
            item(key = "empty") {
                FintechCard { Caption(if (tab == 0) "No accounts yet" else "No cards yet") }
            }
        }
        shown.forEach { account ->
            item(key = account.key) { AccountTile(account, byId[account.sampleMessageId]) { onIntent(HomeContract.Intent.OpenAccount(account.key)) } }
        }

    }
}

/** An account marked as a provident fund: a calmer tile that leads with what has been put in, not spending. */
@Composable
private fun FundTile(fund: AccountSummary, pf: com.zyke.message.domain.pf.ProvidentFund?, onOpen: () -> Unit) {
    val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val asOf = fund.balanceAsOf
    FintechCard(onClick = onOpen) {
        Caption("PROVIDENT FUND  ••  ${fund.last4}")
        Text(
            fund.balance?.let { DisplayFormatters.rupees(it) } ?: "—",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
        Caption(if (asOf != null) "balance as of ${dayFormat.format(asOf)}" else "balance not stated", maxLines = 2)
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (fund.fromPassbook && pf != null) {
                Stat("Last added", pf.latest.added?.let { DisplayFormatters.rupees(it) } ?: "—")
                Stat("Updated", DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(pf.latest.date))
                Stat("Updates", pf.updates.size.toString())
            } else {
                Stat("Added this cycle", DisplayFormatters.rupees(fund.received))
                Stat("Taken out", DisplayFormatters.rupees(fund.spent))
                Stat("Entries", fund.activityCount.toString())
            }
        }
    }
}

/** The provident fund: its balance when a passbook message states one, else what the latest update added. */
@Composable
private fun PfTile(pf: com.zyke.message.domain.pf.ProvidentFund, onOpen: () -> Unit) {
    val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    val latest = pf.latest
    val asOf = pf.balanceAsOf
    FintechCard(onClick = onOpen) {
        Caption("PROVIDENT FUND (EPF)")
        Text(
            (pf.balance ?: latest.added)?.let { DisplayFormatters.rupees(it) } ?: "—",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp)
        )
        Caption(
            if (pf.balance != null && asOf != null) "balance as of ${dayFormat.format(asOf)}"
            else "latest passbook update, ${dayFormat.format(latest.date)}. No balance stated yet.",
            maxLines = 2
        )
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Last added", latest.added?.let { DisplayFormatters.rupees(it) } ?: "—")
            Stat("Updated", dayFormat.format(latest.date))
            Stat("Updates", pf.updates.size.toString())
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
            AccountChip(account, 44.dp)
            Spacer(Modifier.width(12.dp))
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
                        asOf != null && account.balanceAdjustment != null -> "stated ${dayFormat.format(asOf)}, plus later payments"
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
    onStatementDay: (Int?) -> Unit,
    onRemove: () -> Unit,
    onProvidentFund: (Boolean) -> Unit,
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
    val due = account.dueDate
    val asOf = account.balanceAsOf
    var naming by rememberSaveable { mutableStateOf(false) }
    var editingCycle by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pick = rememberAccountPicturePicker(account)
                AccountChip(account, 48.dp, Modifier.clip(RoundedCornerShape(12.dp)).then(if (pick != null) Modifier.clickable(onClickLabel = "Change picture", onClick = pick) else Modifier))
                Spacer(Modifier.width(12.dp))
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
                        asOf != null && account.balanceAdjustment != null ->
                            "stated ${dayFormat.format(asOf)}, ${DisplayFormatters.rupees(account.balanceAdjustment!!.abs())} ${if (account.balanceAdjustment!!.signum() > 0) "in" else "out"} since"
                        asOf != null -> "as of ${dayFormat.format(asOf)}"
                        else -> "Balance not stated"
                    },
                    maxLines = 2
                )
            }

            account.balanceMessageId?.takeIf { !account.isCard }?.let { id ->
                AppButton("See the message this balance came from", { onOpenMessage(id) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }

            RemoveAccountPicture(account)

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

            if (account.kind == AccountKind.CREDIT_CARD) {
                FintechCard(onClick = { editingCycle = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Caption("BILLING CYCLE")
                            Text(
                                account.userCard?.statementDay?.let { "Statement on day $it" } ?: "Calendar months",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(if (account.userCard?.statementDay == null && account.suggestedStatementDay != null) "Set" else "Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    }
                    account.suggestedStatementDay?.let { day ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Caption("Your bills arrive around day $day. Use it as the statement day?", Modifier.weight(1f), maxLines = 3)
                            AppButton("Use day $day", { onStatementDay(day) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                        }
                    }
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

            if (account.kind == AccountKind.BANK_ACCOUNT) {
                FintechCard(onClick = { onProvidentFund(!account.isProvidentFund) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            RowTitle("Provident fund account")
                            Caption("Shown under Savings, and left out of your bank total", maxLines = 2)
                        }
                        androidx.compose.material3.Switch(checked = account.isProvidentFund, onCheckedChange = null)
                    }
                }
            }

            AppButton(
                "Not my ${if (account.isCard) "card" else "account"} — remove",
                { removing = true },
                Modifier.fillMaxWidth(),
                style = ButtonStyle.TONAL
            )
        }
    }

    if (editingCycle) {
        var day by rememberSaveable { mutableStateOf(account.userCard?.statementDay?.toString().orEmpty()) }
        val parsed = day.toIntOrNull()?.takeIf { it in 1..31 }
        AlertDialog(
            onDismissRequest = { editingCycle = false },
            title = { Text("Billing cycle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = day,
                        onValueChange = { day = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        isError = day.isNotEmpty() && parsed == null,
                        label = { Text("Statement day of the month (1-31)") },
                        supportingText = { Text("Leave empty to use calendar months") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                AppButton("Save", { onStatementDay(parsed); editingCycle = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = day.isEmpty() || parsed != null)
            },
            dismissButton = { AppButton("Cancel", { editingCycle = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${account.title}?") },
            text = { Text("It will be left out of your accounts. Its messages stay in your inbox.") },
            confirmButton = { AppButton("Remove", { removing = false; onRemove() }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Cancel", { removing = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf(account.userCard?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
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
                CodeCard(
                    message, now, onCopy = { onCopy(message.id, otp.code) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** Every code that has not yet run out, newest first. */
internal fun freshCodes(state: HomeContract.State, nowMillis: Long): List<ClassifiedMessage> =
    state.messages.filter {
        it.content is MessageContent.Otp && nowMillis - it.message.date in 0 until CODE_LIFE_WINDOW_MS && it.id !in state.userState.archivedIds
    }.take(MAX_FRESH_CODES)

private const val MAX_FRESH_CODES = 6

/** The newest code that has not yet run out, or null. */
internal fun freshCode(state: HomeContract.State, nowMillis: Long): ClassifiedMessage? =
    state.messages.firstOrNull {
        it.content is MessageContent.Otp && nowMillis - it.message.date in 0 until CODE_LIFE_WINDOW_MS && it.id !in state.userState.archivedIds
    }

internal const val CODE_LIFE_WINDOW_MS = 10 * 60 * 1000L
private const val CODE_LIST_WINDOW_MS = 60 * 60 * 1000L
private const val CODES_SHOWN = 5

private const val ACCOUNT_PICTURE_PREFIX = "acct:"

/**
 * The account's own mark: the picture the person chose for it, else its bank's logo. It is the bank's, never the
 * name of whoever was paid in the account's latest message.
 */
@Composable
private fun AccountChip(account: AccountSummary, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val store = LocalImageStore.current
    val keys by (store?.keysWithImage ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())).collectAsState()
    val key = ACCOUNT_PICTURE_PREFIX + account.key
    val bank = remember(account.bankName) { MerchantResolver().resolve(account.bankName.displayString.ifEmpty { account.title }) }
    MerchantChip(
        bank.logo,
        modifier = modifier,
        imageKey = key.takeIf { it in keys },
        name = bank.name,
        description = account.title,
        size = size
    )
}

/** Choosing a picture for the account from the person's own photos; null where pictures are not available. */
@Composable
private fun rememberAccountPicturePicker(account: AccountSummary): (() -> Unit)? {
    val store = LocalImageStore.current ?: return null
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                if (!store.saveChosen(ACCOUNT_PICTURE_PREFIX + account.key, uri)) {
                    android.widget.Toast.makeText(context, "Couldn't use that image", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    return { launcher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** The way back to the bank's logo, once a picture of the person's own has been chosen. */
@Composable
private fun RemoveAccountPicture(account: AccountSummary) {
    val store = LocalImageStore.current ?: return
    val keys by store.keysWithImage.collectAsState()
    val key = ACCOUNT_PICTURE_PREFIX + account.key
    if (key in keys) AppButton("Remove picture", { store.removeChosen(key) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
}
