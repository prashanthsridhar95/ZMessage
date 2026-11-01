package com.zyke.message.presentation.home

import com.zyke.message.presentation.common.AdaptiveSheet
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.presentation.format.FormatCache
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
import com.zyke.message.presentation.common.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.presentation.common.PillSwitch
import com.zyke.message.domain.accounts.AccountActivities
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.presentation.common.DeckCard
import com.zyke.message.presentation.common.faceCard
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
import java.util.Locale

/** What the accounts hold in all, as the one big number on Home. */
@Composable
internal fun BalanceHero(overview: AccountsOverview, onOpen: () -> Unit) {
    FintechCard(onClick = onOpen) {
        Caption("TOTAL BALANCE")
        val total = overview.totalBalance
        if (total != null) {
            com.zyke.message.presentation.common.PrivateAmount(
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
 * The Cards tab's big number: what the cards have billed and still wait to be paid, and what was spent since the last
 * statement that is not on a bill yet. Both are sums over the cards the person sees.
 */
@Composable
internal fun CardsHero(cards: List<AccountSummary>) {
    val billed = remember(cards) { outstandingBilled(cards) }
    val unbilled = remember(cards) { cards.fold(java.math.BigDecimal.ZERO) { sum, card -> sum + (card.unbilled?.takeIf { it.signum() > 0 } ?: java.math.BigDecimal.ZERO) } }
    FintechCard {
        if (cards.isEmpty()) {
            Caption("CARDS")
            Text("Your cards", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Caption("No cards yet", Modifier.padding(top = 2.dp))
            return@FintechCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Caption("BILLED")
                com.zyke.message.presentation.common.PrivateAmount(
                    rememberAnimatedRupees(billed),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Caption("to pay on bills", Modifier.padding(top = 2.dp), maxLines = 2)
            }
            Column(Modifier.weight(1f)) {
                Caption("UNBILLED")
                com.zyke.message.presentation.common.PrivateAmount(
                    rememberAnimatedRupees(unbilled),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1
                )
                Caption("spent, not billed yet", Modifier.padding(top = 2.dp), maxLines = 2)
            }
        }
        Caption(
            DisplayFormatters.count(cards.size, "card", "cards"),
            Modifier.padding(top = 10.dp),
            maxLines = 1
        )
    }
}

/** What is still owed on the cards' latest bills: a bill paid in full counts as nothing, a part-paid one only its rest. */
internal fun outstandingBilled(cards: List<AccountSummary>): java.math.BigDecimal =
    cards.fold(java.math.BigDecimal.ZERO) { sum, card ->
        val bill = card.dueAmount
        sum + when {
            bill == null || card.billPaid -> java.math.BigDecimal.ZERO
            else -> (bill - (card.paidSinceBill ?: java.math.BigDecimal.ZERO)).max(java.math.BigDecimal.ZERO)
        }
    }

/** The Investments tab's big number: what the funds hold (provident funds the person marked, or the one from the passbook). */
@Composable
internal fun InvestmentsHero(funds: List<AccountSummary>, pf: com.zyke.message.domain.pf.ProvidentFund?) {
    val total = remember(funds, pf) {
        val counted = funds.mapNotNull { it.balance }
        val fromPassbook = if (funds.isEmpty()) pf?.balance else null
        val all = counted + listOfNotNull(fromPassbook)
        if (all.isEmpty()) null else all.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
    }
    val count = if (funds.isNotEmpty()) funds.size else if (pf != null) 1 else 0
    FintechCard {
        Caption("TOTAL INVESTMENTS")
        if (total != null) {
            com.zyke.message.presentation.common.PrivateAmount(
                rememberAnimatedRupees(total),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Caption(DisplayFormatters.count(count, "fund", "funds"), Modifier.padding(top = 2.dp), maxLines = 2)
        } else {
            Text("Your investments", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Caption(if (count == 0) "None yet" else "No balance stated yet", Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * A running clock for screens that count down. It ticks once a second while the app is on screen, stops while it is
 * not, and reads the real time the instant the app comes back, so a countdown never shows the moment it was left at.
 * What the countdown is measured against is always the clock on the phone, so time passes whether or not it is drawn.
 */
@Composable
internal fun rememberNowMillis(): Long = rememberNowState().value

/**
 * The same clock as a [State], for a screen that should not recompose as a whole every second: read `.value` only
 * inside the small part that shows the countdown, or from a derivedStateOf that changes rarely.
 */
@Composable
internal fun rememberNowState(): androidx.compose.runtime.State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                // Wake on the next second boundary, so the display changes exactly as the second does.
                kotlinx.coroutines.delay(1_000L - now.longValue % 1_000L)
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
    var tab by rememberSaveable { mutableStateOf(0) }
    val deckState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { cards.size })
    val deckToday = remember(state.now) { java.time.LocalDate.ofInstant(state.now, ZoneId.systemDefault()) }
    val shown = when (tab) {
        0 -> banks
        1 -> cards
        else -> emptyList()
    }
    val pf = state.providentFund
    var showNetWorth by rememberSaveable { mutableStateOf(false) }
    var renamingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var leavingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val today = deckToday
    // Recent balance readings per account, for the trend line: worked out once per change of messages, not per frame.
    val series = remember(state.messages) {
        BalanceReader.history(state.messages, zone).mapValues { (_, list) -> com.zyke.message.domain.accounts.AccountInsights.balanceSeries(list) }
    }
    val openSettings = com.zyke.message.presentation.common.LocalOpenSettings.current
    if (showNetWorth) {
        com.zyke.message.presentation.networth.NetWorthPage(overview.accounts, state.messages, onBack = { showNetWorth = false })
    }

    // Net worth stays put-away until it is asked for: pull down at the top. Kept per visit to this tab.
    var netWorthShown by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = com.zyke.message.presentation.common.rememberReduceMotion()
    val tick = com.zyke.message.presentation.common.rememberTick()
    val pull = rememberPullToReveal(enabled = !netWorthShown) { tick(); netWorthShown = true }

    Box(Modifier.fillMaxSize().then(pull.modifier)) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (netWorthShown) {
            item(key = "net-worth") {
                val appear = remember { androidx.compose.animation.core.MutableTransitionState(reduceMotion).apply { targetState = true } }
                androidx.compose.animation.AnimatedVisibility(
                    appear,
                    enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None
                    else androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn()
                ) {
                    Column {
                        com.zyke.message.presentation.networth.NetWorthCard(overview.accounts, state.messages, onOpen = { showNetWorth = true })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            AppButton(
                                "Hide net worth", { netWorthShown = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                            )
                        }
                    }
                }
            }
        }
        // The big number follows the tab: the bank total only on Accounts, what is billed and unbilled on Cards,
        // and what is invested under Investments.
        item(key = "total") {
            androidx.compose.animation.Crossfade(
                targetState = tab,
                animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(220),
                label = "accounts-hero"
            ) { which ->
                when (which) {
                    1 -> CardsHero(cards)
                    2 -> InvestmentsHero(funds, pf)
                    else -> BalanceHero(overview, onOpen = {})
                }
            }
        }

        item(key = "tabs") {
            UnderlineTabs(
                labels = listOf("Accounts", "Cards", "Investments"),
                counts = listOf(banks.size, cards.size, if (funds.isNotEmpty()) funds.size else if (pf != null) 1 else 0),
                selectedIndex = tab,
                onSelect = { tab = it },
                showDivider = false
            )
        }

        if (tab == 2) {
            if (pf == null && funds.isEmpty()) {
                item(key = "pf-empty") {
                    com.zyke.message.presentation.common.EmptyState(
                        "No provident fund yet",
                        "Passbook messages from EPFO show up here, and you can mark any account as a provident fund from its sheet."
                    )
                }
            }
            if (pf != null && funds.isEmpty()) item(key = "pf") { PfTile(pf) { onIntent(HomeContract.Intent.OpenMessage(pf.latest.messageId)) } }
            funds.forEach { fund ->
                item(key = fund.key) { FundTile(fund, pf) { onIntent(HomeContract.Intent.OpenAccount(fund.key)) } }
            }
        } else if (shown.isEmpty()) {
            item(key = "empty") {
                com.zyke.message.presentation.common.EmptyState(
                    if (tab == 0) "No accounts yet" else "No cards yet",
                    if (tab == 0) "Bank accounts appear here once a message with a balance arrives." else "Cards appear here once a card payment or bill message arrives."
                )
            }
        }
        if (tab == 1 && cards.isNotEmpty()) {
            // Cards are shown as cards: a deck to swipe, with the one in front opened in full on a tap.
            item(key = "card-deck") {
                val deck = cards.map { DeckCard(it.key, it.faceCard(), cardFooter(it, deckToday)) }
                com.zyke.message.presentation.common.CardDeck(
                    deck, deckState,
                    onOpen = { onIntent(HomeContract.Intent.OpenAccount(it.key)) }
                )
            }
            val front = cards.getOrNull(deckState.currentPage.coerceIn(0, cards.lastIndex))
            if (front != null) {
                item(key = "card-front-" + front.key) {
                    CardStatusPanel(
                        front, today,
                        onOpen = { onIntent(HomeContract.Intent.OpenAccount(front.key)) },
                        onRename = { renamingKey = front.key },
                        onLeaveOut = { leavingKey = front.key }
                    )
                }
            }
            item(key = "add-card") {
                AppButton("Add or edit cards", openSettings, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
            }
        } else {
            shown.forEach { account ->
                item(key = account.key) {
                    BankAccountTile(
                        account, series[account.last4].orEmpty(), today,
                        onOpen = { onIntent(HomeContract.Intent.OpenAccount(account.key)) },
                        onRename = { renamingKey = account.key },
                        onLeaveOut = { leavingKey = account.key }
                    )
                }
            }
        }

    }
    PullToRevealIndicator(pull.progress, Modifier.align(Alignment.TopCenter))
    }

    renamingKey?.let { key ->
        val target = overview.accounts.firstOrNull { it.key == key }
        if (target == null) renamingKey = null else {
            var name by rememberSaveable(key) { mutableStateOf(target.userCard?.name.orEmpty()) }
            com.zyke.message.presentation.common.FormSheet(
                title = "Name this account", onDismiss = { renamingKey = null },
                primaryLabel = "Save", primaryEnabled = name.isNotBlank(), hint = "Type a name",
                onPrimary = { onIntent(HomeContract.Intent.NameAccount(key, name)); renamingKey = null }
            ) {
                com.zyke.message.presentation.common.FormField(
                    "Name", name, { name = it }, placeholder = "e.g. Salary account",
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
                )
            }
        }
    }
    leavingKey?.let { key ->
        val target = overview.accounts.firstOrNull { it.key == key }
        if (target == null) leavingKey = null else AlertDialog(
            onDismissRequest = { leavingKey = null },
            title = { Text("Leave out ${target.title}?") },
            text = { Text("It will be left out of your accounts and totals. Its messages stay in your inbox.") },
            confirmButton = { AppButton("Leave out", { leavingKey = null; onIntent(HomeContract.Intent.RemoveAccount(key)) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Cancel", { leavingKey = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

/** The short status on a card's face: when the bill is due, else what has been spent since the last one. */
internal fun cardFooter(account: AccountSummary, today: java.time.LocalDate): String? {
    if (!account.isCard) return null
    val due = account.dueDate
    val unbilled = account.unbilled?.takeIf { it.signum() > 0 }
    val dueAmount = account.dueAmount
    return when {
        account.billPaid && unbilled != null -> "${DisplayFormatters.rupees(unbilled)} unbilled"
        due != null && !due.isBefore(today) && dueAmount != null && dueAmount.signum() > 0 -> DisplayFormatters.dueLabel(due, today)
        unbilled != null -> "${DisplayFormatters.rupees(unbilled)} unbilled"
        else -> null
    }
}

/** An account marked as a provident fund: a calmer tile that leads with what has been put in, not spending. */
@Composable
private fun FundTile(fund: AccountSummary, pf: com.zyke.message.domain.pf.ProvidentFund?, onOpen: () -> Unit) {
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
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
                Stat("Updated", FormatCache.pattern("d MMM yyyy", Locale.getDefault()).format(pf.latest.date))
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
    val dayFormat = FormatCache.pattern("d MMM yyyy", Locale.getDefault())
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
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    val history = remember(state.messages, account.last4) { BalanceReader.history(state.messages, zone)[account.last4].orEmpty() }
    // Activity can be read for the calendar month, or for the card's own cycle (its statement) or the pay cycle.
    var period by rememberSaveable { mutableStateOf(0) }
    val today = remember(state.now) { java.time.LocalDate.ofInstant(state.now, zone) }
    val cycleLabel = if (account.kind == AccountKind.CREDIT_CARD && account.statementOn != null) "Card cycle" else "Pay cycle"
    val periodStart = remember(period, today, account.statementOn, state.cycle) {
        when {
            period == 0 -> today.withDayOfMonth(1)
            account.kind == AccountKind.CREDIT_CARD && account.statementOn != null -> account.statementOn!!.plusDays(1)
            else -> state.cycle.containing(today).start
        }
    }
    val activity = remember(state.messages, account.key, periodStart, today) {
        AccountActivities.build(state.messages, account.last4, account.isCard, periodStart, today, zone)
    }
    val due = account.dueDate
    val asOf = account.balanceAsOf
    var naming by rememberSaveable { mutableStateOf(false) }
    var editingCycle by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }

    AdaptiveSheet(account.title, onDismiss, asPage = true) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pick = com.zyke.message.presentation.common.rememberPicturePicker(ACCOUNT_PICTURE_PREFIX + account.key, account.title)
                AccountChip(account, 48.dp, Modifier.clip(RoundedCornerShape(12.dp)).then(if (pick != null) Modifier.clickable(onClickLabel = "Change picture", onClick = pick) else Modifier))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Caption("${account.kind.label} •• ${account.last4}")
                }
                AppButton("Rename", { naming = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }

            Column {
                Caption(
                    when {
                        account.kind == AccountKind.CREDIT_CARD && account.unbilled != null -> "UNBILLED SPEND"
                        account.isCard -> "LATEST BILL"
                        else -> "BALANCE"
                    }
                )
                Text(
                    (if (account.kind == AccountKind.CREDIT_CARD && account.unbilled != null) account.unbilled else if (account.isCard) account.dueAmount else account.balance)
                        ?.let { DisplayFormatters.rupees(it) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Caption(
                    when {
                        account.kind == AccountKind.CREDIT_CARD && account.unbilled != null ->
                            "spent since the ${dayFormat.format(account.statementOn!!)} statement"
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

            // The card's bill, next to what is not billed yet.
            if (account.kind == AccountKind.CREDIT_CARD && (account.dueAmount != null || account.unbilled != null)) {
                FintechCard {
                    Caption("THE BILL")
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Billed", account.dueAmount?.let { DisplayFormatters.rupees(it) } ?: "—")
                        Stat("Due", due?.let { dayFormat.format(it) } ?: "—")
                        Stat("Minimum", account.minimumDue?.let { DisplayFormatters.rupees(it) } ?: "—")
                    }
                    account.paidSinceBill?.let {
                        Caption("${DisplayFormatters.rupees(it)} paid since the bill came", Modifier.padding(top = 10.dp), maxLines = 2)
                    }
                    account.unbilled?.let {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Unbilled so far", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            Text(DisplayFormatters.rupees(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Activity for this month, or for the cycle, with its totals.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PillSwitch(listOf("This month", cycleLabel), period, { period = it })
                FintechCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Spent", DisplayFormatters.rupees(activity.spent))
                        Stat(if (account.isCard) "Credited" else "Received", DisplayFormatters.rupees(activity.received))
                        Stat("Payments", activity.messages.size.toString())
                    }
                    Caption(
                        "${dayFormat.format(periodStart)} – ${dayFormat.format(today)}",
                        Modifier.padding(top = 10.dp)
                    )
                }
                if (activity.messages.isEmpty()) {
                    Caption("Nothing in this period yet")
                } else {
                    Column {
                        RowTitle("Activity")
                        activity.messages.forEachIndexed { index, message ->
                            if (index > 0) HorizontalDivider()
                            RecentRow(message, onOpen = { onOpenMessage(message.id) })
                        }
                    }
                }
            }

            if (account.kind == AccountKind.BANK_ACCOUNT) {
                FintechCard(onClick = { onProvidentFund(!account.isProvidentFund) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            RowTitle("Provident fund account")
                            Caption("Shown under Investments, and left out of your bank total", maxLines = 2)
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
        com.zyke.message.presentation.common.FormSheet(
            title = "Billing cycle",
            onDismiss = { editingCycle = false },
            primaryLabel = "Save",
            primaryEnabled = true,
            onPrimary = { onStatementDay(day.toIntOrNull()?.takeIf { it in 1..31 }); editingCycle = false }
        ) {
            com.zyke.message.presentation.common.DayOfMonthField(
                "Statement day of the month", day, { day = it },
                supporting = "Leave it unset to use calendar months"
            )
        }
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
        com.zyke.message.presentation.common.FormSheet(
            title = "Name this account",
            onDismiss = { naming = false },
            primaryLabel = "Save",
            primaryEnabled = name.isNotBlank(),
            hint = "Type a name",
            onPrimary = { onName(name); naming = false }
        ) {
            com.zyke.message.presentation.common.FormField(
                "Name", name, { name = it }, placeholder = "e.g. Salary account",
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
            )
        }

    }
}


// ---- codes

/** Fresh one-time codes with their countdowns; opened from the code card on Home. */
@Composable
internal fun CodesSheet(state: HomeContract.State, onCopy: (Long, String) -> Unit, onDismiss: () -> Unit) {
    val now = rememberNowMillis()
    val codes = state.messages.filter {
        it.content is MessageContent.Otp && it.message.date >= now - CODE_LIST_WINDOW_MS && it.id !in state.userState.archivedIds
    }.take(CODES_SHOWN)

    AdaptiveSheet("Codes", onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            if (codes.isEmpty()) {
                Caption("A code shows up here the moment it arrives.", Modifier.padding(vertical = 16.dp), maxLines = 2)
            }
            codes.forEach { message ->
                val otp = message.content as MessageContent.Otp
                CodeCard(
                    message, now, onCopy = { onCopy(message.id, otp.code) },
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}

/** Every code that has not yet run out, newest first. */
internal fun freshCodes(state: HomeContract.State, nowMillis: Long): List<ClassifiedMessage> =
    freshCodes(otpCandidates(state), nowMillis)

/** Every code not put away, newest first; the part of [freshCodes] that does not change as the seconds pass. */
internal fun otpCandidates(state: HomeContract.State): List<ClassifiedMessage> {
    val archived = state.userState.archivedIds
    return state.messages.filter { it.content is MessageContent.Otp && it.id !in archived }
}

/** Those of [candidates] still inside their lifetime at [nowMillis]. A short list, so asking every second is cheap. */
internal fun freshCodes(candidates: List<ClassifiedMessage>, nowMillis: Long): List<ClassifiedMessage> =
    candidates.filter { nowMillis - it.message.date in 0 until CODE_LIFE_WINDOW_MS }.take(MAX_FRESH_CODES)

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
internal fun AccountChip(account: AccountSummary, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
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

/** The way back to the bank's logo, once a picture of the person's own has been chosen. */
@Composable
private fun RemoveAccountPicture(account: AccountSummary) {
    val store = LocalImageStore.current ?: return
    val keys by store.keysWithImage.collectAsState()
    val key = ACCOUNT_PICTURE_PREFIX + account.key
    if (key in keys) AppButton("Remove picture", { store.removeChosen(key) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
}

/** Where a pull-down at the top of a list has got to: [progress] runs 0 to 1, and reaching 1 fires the reveal once. */
internal class PullToReveal(val modifier: Modifier, private val state: androidx.compose.runtime.State<Float>) {
    val progress: () -> Float = { state.value }
}

/**
 * Pull-down-at-the-top as a determinate gesture. Whatever the list itself did not use of a downward drag (so it is at
 * the top) fills the progress; at full, [onReveal] runs and the pull resets. Letting go early just empties it.
 */
@Composable
internal fun rememberPullToReveal(enabled: Boolean, onReveal: () -> Unit): PullToReveal {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val full = remember(density) { with(density) { 140.dp.toPx() } }
    val pulled = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val reveal = androidx.compose.runtime.rememberUpdatedState(onReveal)
    val on = androidx.compose.runtime.rememberUpdatedState(enabled)
    val fraction = remember(full) { androidx.compose.runtime.derivedStateOf { (pulled.floatValue / full).coerceIn(0f, 1f) } }
    val connection = remember(full) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // Pushing back up while a pull is under way takes the pull back first.
                if (!on.value || pulled.floatValue <= 0f || available.y >= 0f) return androidx.compose.ui.geometry.Offset.Zero
                val back = (-available.y).coerceAtMost(pulled.floatValue)
                pulled.floatValue -= back
                return androidx.compose.ui.geometry.Offset(0f, -back)
            }

            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (!on.value || available.y <= 0f || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                pulled.floatValue += available.y * 0.6f
                if (pulled.floatValue >= full) {
                    pulled.floatValue = 0f
                    reveal.value()
                }
                return androidx.compose.ui.geometry.Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                pulled.floatValue = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    return remember(connection) { PullToReveal(Modifier.nestedScroll(connection), fraction) }
}

/** A slim determinate bar with a hint, drawn over the top of the list so nothing moves; invisible until a pull starts. */
@Composable
internal fun PullToRevealIndicator(progress: () -> Float, modifier: Modifier = Modifier) {
    val p = progress()
    if (p <= 0f) return
    androidx.compose.material3.Surface(
        modifier.padding(top = 8.dp).semantics { contentDescription = "Pull to reveal net worth, ${(p * 100).toInt()} percent" },
        shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Caption(if (p >= 0.85f) "Almost there" else "Pull to reveal net worth")
            androidx.compose.material3.LinearProgressIndicator(
                progress = { p }, modifier = Modifier.padding(top = 6.dp).width(160.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        }
    }
}
