package com.zyke.message.presentation.home

import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.presentation.tour.TourScroll
import com.zyke.message.presentation.investments.investmentItems
import com.zyke.message.presentation.tour.tourAnchor
import com.zyke.message.presentation.common.AdaptiveSheet
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.common.UnderlineTabs
import kotlinx.coroutines.launch
import com.zyke.message.presentation.common.LocalImageStore
import com.zyke.message.domain.classification.MerchantResolver
import androidx.compose.runtime.collectAsState
import com.zyke.message.presentation.common.FintechCard
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.SkeletonBlock
import com.zyke.message.presentation.motion.fadeInOnce
import com.zyke.message.presentation.motion.rememberEnterGate
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.zyke.message.presentation.common.toggleRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.headerContentColor
import com.zyke.message.presentation.motion.AnimatedAmount
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
import com.zyke.message.R
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
internal fun BalanceHero(overview: AccountsOverview, modifier: Modifier = Modifier, intro: Boolean = false, onOpen: () -> Unit) {
    // The one hero of Home: tinted with the accent, larger corners, the figure counts up when the screen first appears.
    FintechCard(modifier, onClick = onOpen, level = com.zyke.message.presentation.theme.Tonal.Hero) {
        com.zyke.message.presentation.common.CardHeader("Total balance")
        val total = overview.totalBalance
        if (total != null) {
            com.zyke.message.presentation.motion.AnimatedAmount(
                total,
                Modifier.padding(top = Spacing.xs),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                fit = true,
                fromZero = intro
            )
            Caption(
                DisplayFormatters.count(overview.countedBalances, "bank account", "bank accounts"),
                Modifier.padding(top = Spacing.xs),
                maxLines = 2
            )
        } else {
            Text("Your accounts", Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Caption("No balances yet", Modifier.padding(top = Spacing.xs))
        }
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

/** The header's key while the first read is still going (a skeleton), apart from the three sub-tabs 0..2. */
private const val HEADER_LOADING = -1

private const val PF_CHECK = "pf"
private val PF_CHECK_REQUEST = com.zyke.message.domain.balance.BalanceEnquiry.PF_REQUEST_KEY

@Composable
internal fun AccountsPage(state: HomeContract.State, onIntent: (HomeContract.Intent) -> Unit) {
    val overview = state.accounts
    val banks = overview.accounts.filter { !it.isCard && !it.isProvidentFund }.distinctBy { it.key }
    val funds = overview.accounts.filter { it.isProvidentFund }.distinctBy { it.key }
    val cards = overview.accounts.filter { it.isCard }.distinctBy { it.key }
    var tab by rememberSaveable { mutableStateOf(0) }
    // Nothing to show yet and still reading: a still skeleton, then a crossfade to the real thing.
    val loading = state.isLoading && overview.accounts.isEmpty()
    // Each sub-tab's hero counts up once, the first time that sub-tab is shown, and is still on every later visit.
    val heroGates = listOf(rememberEnterGate(), rememberEnterGate(), rememberEnterGate())
    val motion = rememberMotion()
    // Switching sub-tab: the body slides 24 dp along the shared axis while it fades in (the header crossfades by itself).
    var previousTab by remember { mutableStateOf(tab) }
    val slideDirection = remember(tab) { if (tab >= previousTab) 1f else -1f }
    val tabIn = remember(tab) { Animatable(if (previousTab != tab && !motion.reduced) 0f else 1f) }
    SideEffect { previousTab = tab }
    LaunchedEffect(tab) { if (tabIn.value < 1f) tabIn.animateTo(1f, motion.tween(Motion.TAB_MS, Motion.Standard)) }
    val slidePx = with(LocalDensity.current) { Motion.TAB_SLIDE_DP.dp.toPx() }
    val bodyIn = Modifier.graphicsLayer {
        val shown = tabIn.value
        alpha = shown
        translationX = (1f - shown) * slidePx * slideDirection
    }
    val haptics = rememberHaptics()
    val deckState = com.zyke.message.presentation.common.rememberDeckState()
    val bankDeck = com.zyke.message.presentation.common.rememberDeckState()
    val deckToday = remember(state.now) { java.time.LocalDate.ofInstant(state.now, ZoneId.systemDefault()) }
    val shown = when (tab) {
        0 -> banks
        1 -> cards
        else -> emptyList()
    }
    val pf = state.providentFund
    // What the provident fund(s) hold, from messages: counted once in the Investments total, as net worth counts it.
    val pfTotal = remember(funds, pf) {
        val counted = funds.mapNotNull { it.balance }
        val fromPassbook = if (funds.isEmpty()) pf?.balance else null
        (counted + listOfNotNull(fromPassbook)).takeIf { it.isNotEmpty() }?.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
    }
    val invest = com.zyke.message.presentation.investments.rememberInvestmentsModel(pfTotal, deckToday)
    com.zyke.message.presentation.investments.MigrateNetWorthEntries()
    com.zyke.message.presentation.investments.HandleInvestmentRequests(invest) { tab = 2 }
    var showNetWorth by rememberSaveable { mutableStateOf(false) }
    var renamingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var leavingKey by rememberSaveable { mutableStateOf<String?>(null) }
    // "Check balance by SMS": an account's key, or PF_CHECK for the provident fund.
    var checkingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsKey by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val today = deckToday
    // Recent balance readings per account, for the trend line: worked out once per change of messages, not per frame.
    val series = remember(state.messages) {
        BalanceReader.history(state.messages, zone).mapValues { (_, list) -> com.zyke.message.domain.accounts.AccountInsights.balanceSeries(list) }
    }
    val openSettings = com.zyke.message.presentation.common.LocalOpenSettings.current
    val markPaidBill = rememberMarkPaid(onIntent)
    if (showNetWorth) {
        com.zyke.message.presentation.networth.NetWorthPage(overview.accounts, state.messages, onBack = { showNetWorth = false })
    }

    // Net worth stays put-away until it is asked for: pull down at the top. Kept per visit to this tab.
    var netWorthShown by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = motion.reduced
        val pull = rememberPullToReveal(enabled = !netWorthShown) { haptics.tick(); netWorthShown = true }

    // The card and the bank account in front of their decks: what the actions and the spend card below speak about.
    val frontCard = cards.getOrNull(com.zyke.message.domain.cards.DeckModel.clamp(deckState.currentPage, cards.size))
    val frontBank = banks.getOrNull(com.zyke.message.domain.cards.DeckModel.clamp(bankDeck.currentPage, banks.size))
    // This card's open bill, from the upcoming list: it has what "Mark as paid" stores. A bill that is open in the messages but
    // gone from that list (and not paid in them) was marked as paid by hand.
    val frontBill = remember(state.upcoming, frontCard?.last4) {
        frontCard?.let { front ->
            state.upcoming.firstOrNull { item ->
                val content = item.message.content as? com.zyke.message.domain.model.MessageContent.CardBill
                item.kind == com.zyke.message.domain.home.UpcomingKind.CARD_BILL && content != null &&
                    content.accountNumber.filter { it.isDigit() }.takeLast(4) == front.last4
            }
        }
    }
    val cardModel = if (tab == 1 && frontCard != null) {
        val frontDue = frontCard.dueDate
        val handMarked = !state.isLoading && frontBill == null && !frontCard.billPaid && frontCard.billPartPaid == null && frontDue != null &&
            !frontDue.isBefore(today) && !frontDue.isAfter(today.plusDays(30))
        rememberCardTabModel(frontCard, state.messages, today, zone, frontBill, handMarked)
    } else null

    val accountsList = androidx.compose.foundation.lazy.rememberLazyListState()
    TourScroll(TourTab.ACCOUNTS, accountsList)
    Box(Modifier.fillMaxSize().then(pull.modifier)) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        state = accountsList,
        // No top padding: the header region starts at the top edge. The sides stay at the gutter; the region bleeds through them.
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.lg, end = Spacing.lg, top = 0.dp, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        if (netWorthShown) {
            item(key = "net-worth") {
                val appear = remember { androidx.compose.animation.core.MutableTransitionState(reduceMotion).apply { targetState = true } }
                androidx.compose.animation.AnimatedVisibility(
                    appear,
                    enter = motion.appear
                ) {
                    Column(Modifier.padding(top = Spacing.lg)) {
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
        item(key = "tabs") {
            UnderlineTabs(
                modifier = Modifier.tourAnchor(TourAnchor.ACCOUNTS_TABS),
                labels = listOf("Accounts", "Cards", "Investments"),
                counts = listOf(banks.size, cards.size, invest.summary.count + (if (funds.isNotEmpty()) funds.size else if (pf != null) 1 else 0)),
                selectedIndex = tab,
                onSelect = { tab = it },
                showDivider = false
            )
        }

        // The header region follows the tab: the total and a deck of bank accounts; the deck of cards; what is invested. It is
        // one item, so its content crossfades (and its height eases) when the sub-tab changes, instead of the list being rebuilt.
        item(key = "header") {
            com.zyke.message.presentation.common.HeaderRegion {
                CrossfadeContent(if (loading) HEADER_LOADING else tab, label = "accounts header") { which ->
                    Column(Modifier.fillMaxWidth()) {
                        when (which) {
                            HEADER_LOADING -> HeaderSkeleton()
                            1 -> CardsHeaderContent(
                                cards, deckState, deckToday,
                                onOpen = { onIntent(HomeContract.Intent.OpenAccount(it.key)) },
                                onRename = { renamingKey = it.key }, onLeaveOut = { leavingKey = it.key },
                                deckModifier = Modifier.tourAnchor(TourAnchor.ACCOUNTS_DECK)
                            )
                            2 -> InvestmentsHeaderContent(
                                invest.summary, if (funds.isNotEmpty()) funds.size else if (pf != null) 1 else 0,
                                entering = heroGates[2].heroEntering()
                            )
                            else -> BanksHeaderContent(
                                overview, banks, bankDeck, deckToday,
                                onOpen = { onIntent(HomeContract.Intent.OpenAccount(it.key)) },
                                onRename = { renamingKey = it.key }, onLeaveOut = { leavingKey = it.key },
                                onCheckBalance = { checkingKey = it.key }, onBalanceSettings = { settingsKey = it.key },
                                entering = heroGates[0].heroEntering(),
                                deckModifier = Modifier.tourAnchor(TourAnchor.ACCOUNTS_DECK)
                            )
                        }
                    }
                }
            }
        }

        if (loading) {
            item(key = "body-loading") { BodySkeleton(bodyIn) }
        } else if (tab == 2) {
            val pfActions = if (pf != null || funds.isNotEmpty()) listOf(
                com.zyke.message.presentation.common.ActionSpec("Check PF balance", R.drawable.baseline_message_24, { checkingKey = PF_CHECK })
            ) else emptyList()
            investmentItems(invest, pfActions, bodyIn) {
                if (pf != null && funds.isEmpty()) item(key = "pf") { PfTile(pf, { checkingKey = PF_CHECK }, bodyIn) { onIntent(HomeContract.Intent.OpenMessage(pf.latest.messageId)) } }
                funds.forEachIndexed { index, fund ->
                    // One "Check PF balance" for the fund, on the first tile.
                    item(key = fund.key) { FundTile(fund, pf, if (index == 0) ({ checkingKey = PF_CHECK }) else null, bodyIn) { onIntent(HomeContract.Intent.OpenAccount(fund.key)) } }
                }
            }
        } else if (shown.isEmpty()) {
            item(key = "empty-$tab") {
                if (tab == 0) {
                    com.zyke.message.presentation.common.EmptyState(
                        "No accounts yet", "They appear when your bank texts you a balance.",
                        bodyIn, icon = R.drawable.ic_accounts_24
                    )
                } else {
                    com.zyke.message.presentation.common.EmptyState(
                        "No cards yet", "They appear when your bank texts you about a card.",
                        bodyIn, icon = R.drawable.ic_card_24, actionLabel = "Add a card", onAction = openSettings
                    )
                }
            }
        }
        if (tab == 1 && cardModel != null) {
            cardBodyItems(
                cardModel,
                onPay = { link -> onIntent(HomeContract.Intent.OpenLink(link)) },
                onMarkPaid = { frontBill?.let { markPaidBill(it) } },
                onOpen = { onIntent(HomeContract.Intent.OpenAccount(cardModel.card.key)) },
                onEditCards = openSettings,
                itemModifier = bodyIn
            )
        } else if (tab == 0 && !loading) {
            if (frontBank != null) bankBodyItems(
                frontBank, state.messages, today, zone,
                onOpen = { onIntent(HomeContract.Intent.OpenAccount(frontBank.key)) },
                onSetBalance = { renamingKey = frontBank.key },
                onCheckBalance = { checkingKey = frontBank.key },
                itemModifier = bodyIn
            )
        }

    }
    PullToRevealIndicator(pull.progress, Modifier.align(Alignment.TopCenter))
    }

    com.zyke.message.presentation.investments.InvestmentsDialogs(invest)

    checkingKey?.let { key ->
        val account = overview.accounts.firstOrNull { it.key == key }
        if (key == PF_CHECK) {
            CheckBalanceSheet(
                BalanceTarget(com.zyke.message.domain.balance.BalanceEnquiry.PF_REQUEST_KEY, com.zyke.message.domain.model.BankName.OTHERS, "EPFO", "", isProvidentFund = true),
                maxOf(pf?.balanceAt ?: 0L, funds.maxOfOrNull { it.balanceAt } ?: 0L),
                onDismiss = { checkingKey = null }
            )
        } else if (account == null) checkingKey = null else {
            CheckBalanceSheet(
                BalanceTarget(account.key, account.bankName, account.bankLabel, account.last4, isProvidentFund = false),
                account.balanceAt,
                onDismiss = { checkingKey = null }
            )
        }
    }
    settingsKey?.let { key ->
        val account = overview.accounts.firstOrNull { it.key == key }
        if (account == null) settingsKey = null else {
            CheckBalanceSheet(
                BalanceTarget(account.key, account.bankName, account.bankLabel, account.last4, isProvidentFund = false),
                account.balanceAt, onDismiss = { settingsKey = null }, settingsOnly = true
            )
        }
    }
    renamingKey?.let { key ->
        val target = overview.accounts.firstOrNull { it.key == key }
        if (target == null) renamingKey = null else {
            EditAccountSheet(
                target,
                onSave = { name, bank, kind, balance, network -> onIntent(HomeContract.Intent.EditAccount(key, name, bank, kind, balance, network)); renamingKey = null },
                onDismiss = { renamingKey = null }
            )
        }
    }
    leavingKey?.let { key ->
        val target = overview.accounts.firstOrNull { it.key == key }
        if (target == null) leavingKey = null else AlertDialog(
            onDismissRequest = { leavingKey = null },
            title = { Text("Leave out ${target.title}?") },
            text = { Text("It will be left out of your accounts and totals. Its messages stay in your inbox.") },
            confirmButton = { AppButton("Leave out", { haptics.reject(); leavingKey = null; onIntent(HomeContract.Intent.RemoveAccount(key)) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
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
private fun FundTile(fund: AccountSummary, pf: com.zyke.message.domain.pf.ProvidentFund?, onCheck: (() -> Unit)?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    val asOf = fund.balanceAsOf
    val balance = fund.balance
    FintechCard(modifier, onClick = onOpen) {
        CardHeader("Provident fund  \u2022\u2022  ${fund.last4}")
        if (balance != null) {
            AnimatedAmount(balance, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
        } else {
            PrivateAmount("\u2014", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
        }
        Caption(if (asOf != null) "balance as of ${dayFormat.format(asOf)}" else "balance not stated", maxLines = 2)
        HorizontalDivider(Modifier.padding(vertical = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (fund.fromPassbook && pf != null) {
                Stat("Last added", pf.latest.added?.let { DisplayFormatters.rupees(it) } ?: "\u2014")
                Stat("Updated", FormatCache.pattern("d MMM yyyy", Locale.getDefault()).format(pf.latest.date))
                Stat("Updates", pf.updates.size.toString())
            } else {
                Stat("Added this cycle", DisplayFormatters.rupees(fund.received))
                Stat("Taken out", DisplayFormatters.rupees(fund.spent))
                Stat("Entries", fund.activityCount.toString())
            }
        }
        // The provident fund always shows its sheet first (the UAN is typed there); the slot still shows the progress.
        if (onCheck != null) {
            val (state, progress) = rememberCheckState(PF_CHECK_REQUEST, maxOf(pf?.balanceAt ?: 0L, fund.balanceAt))
            CheckBalanceRow("Check PF balance", state, progress, onCheck, Modifier.padding(top = Spacing.sm))
        }
    }
}

/** The provident fund: its balance when a passbook message states one, else what the latest update added. */
@Composable
private fun PfTile(pf: com.zyke.message.domain.pf.ProvidentFund, onCheck: () -> Unit, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val dayFormat = FormatCache.pattern("d MMM yyyy", Locale.getDefault())
    val latest = pf.latest
    val asOf = pf.balanceAsOf
    val shown = pf.balance ?: latest.added
    FintechCard(modifier, onClick = onOpen) {
        CardHeader("Provident fund (EPF)")
        if (shown != null) {
            AnimatedAmount(shown, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
        } else {
            PrivateAmount("\u2014", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
        }
        Caption(
            if (pf.balance != null && asOf != null) "balance as of ${dayFormat.format(asOf)}"
            else "latest passbook update, ${dayFormat.format(latest.date)}. No balance stated yet.",
            maxLines = 2
        )
        HorizontalDivider(Modifier.padding(vertical = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Last added", latest.added?.let { DisplayFormatters.rupees(it) } ?: "\u2014")
            Stat("Updated", dayFormat.format(latest.date))
            Stat("Updates", pf.updates.size.toString())
        }
        val (state, progress) = rememberCheckState(PF_CHECK_REQUEST, pf.balanceAt)
        CheckBalanceRow("Check PF balance", state, progress, onCheck, Modifier.padding(top = Spacing.sm))
    }
}

private val HEADER_DECK_SKELETON = 150.dp
private val BODY_SKELETON_CARD = 140.dp
private val SKELETON_CIRCLE = 48.dp

/** The header while the first read is going: still blocks in the region's own ink, where the label, the figure and the deck will be. */
@Composable
private fun HeaderSkeleton() {
    val ink = headerContentColor().copy(alpha = 0.14f)
    Column(
        Modifier.fillMaxWidth().fadeInOnce().clearAndSetSemantics { contentDescription = "Loading your accounts" },
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Spacer(Modifier.fillMaxWidth(0.3f).height(Spacing.md).background(ink, AppShapes.chip))
        Spacer(Modifier.fillMaxWidth(0.6f).height(Spacing.xxxl).background(ink, AppShapes.chip))
        Spacer(Modifier.fillMaxWidth().height(HEADER_DECK_SKELETON).background(ink, AppShapes.card))
    }
}

/** The body while the first read is going: the action circles and two cards, still. */
@Composable
private fun BodySkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().fadeInOnce().clearAndSetSemantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(3) { SkeletonBlock(Modifier.size(SKELETON_CIRCLE), corner = SKELETON_CIRCLE / 2) }
        }
        SkeletonBlock(Modifier.fillMaxWidth().height(BODY_SKELETON_CARD), corner = Radius.card)
        SkeletonBlock(Modifier.fillMaxWidth().height(BODY_SKELETON_CARD), corner = Radius.card)
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
    onEdit: (String, String, AccountKind?, java.math.BigDecimal?, com.zyke.message.domain.cards.CardNetwork?) -> Unit,
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
        AccountActivities.build(state.messages, account.last4, account.messagesAreCard, periodStart, today, zone)
    }
    val due = account.dueDate
    val asOf = account.balanceAsOf
    var naming by rememberSaveable { mutableStateOf(false) }
    var editingCycle by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()

    AdaptiveSheet(account.title, onDismiss, asPage = true) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pick = com.zyke.message.presentation.common.rememberPicturePicker(ACCOUNT_PICTURE_PREFIX + account.key, account.title)
                AccountChip(account, 48.dp, Modifier.clip(AppShapes.chip).then(if (pick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Change picture", onClick = pick) else Modifier))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Caption("${account.kind.label} •• ${account.last4}")
                }
                AppButton("Edit", { naming = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }

            Column {
                Caption(
                    when {
                        account.kind == AccountKind.CREDIT_CARD && account.unbilled != null -> "UNBILLED SPEND"
                        account.isCard -> "LATEST BILL"
                        else -> "BALANCE"
                    }
                )
                // Local copy: these come from another module, so they cannot be smart-cast after a null check.
                val figure: java.math.BigDecimal? = if (account.kind == AccountKind.CREDIT_CARD && account.unbilled != null) account.unbilled else if (account.isCard) account.dueAmount else account.balance
                if (figure != null) {
                    AnimatedAmount(figure, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
                } else {
                    PrivateAmount("—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
                }
                Caption(
                    when {
                        account.kind == AccountKind.CREDIT_CARD && account.unbilled != null ->
                            "spent since the ${dayFormat.format(account.statementOn!!)} statement"
                        account.isCard && due != null ->
                            "due ${dayFormat.format(due)}" + (account.minimumDue?.let { " · minimum ${DisplayFormatters.rupees(it)}" } ?: "")
                        !account.isCard && balanceWhy(account) != null -> balanceWhy(account)!!
                        asOf != null -> "as of ${dayFormat.format(asOf)}"
                        else -> "Balance not stated"
                    },
                    maxLines = 3
                )
            }

            if (!account.isCard && (account.balance == null || account.adjustmentRefused || account.paymentsNotCounted > 0)) {
                AppButton(if (account.balance == null) "Set balance" else "Fix balance", { naming = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
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
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
            }

            if (account.kind == AccountKind.CREDIT_CARD) {
                FintechCard(onClick = { editingCycle = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CardHeader("Billing cycle")
                            Text(
                                account.userCard?.statementDay?.let { "Statement on day $it" } ?: "Calendar months",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(if (account.userCard?.statementDay == null && account.suggestedStatementDay != null) "Set" else "Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    }
                    account.suggestedStatementDay?.let { day ->
                        Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Caption("Your bills arrive around day $day. Use it as the statement day?", Modifier.weight(1f), maxLines = 3)
                            AppButton("Use day $day", { onStatementDay(day) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }

            // The card's bill, next to what is not billed yet.
            if (account.kind == AccountKind.CREDIT_CARD && (account.dueAmount != null || account.unbilled != null)) {
                FintechCard {
                    CardHeader("The bill")
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Billed", account.dueAmount?.let { DisplayFormatters.rupees(it) } ?: "—")
                        Stat("Due", due?.let { dayFormat.format(it) } ?: "—")
                        Stat("Minimum", account.minimumDue?.let { DisplayFormatters.rupees(it) } ?: "—")
                    }
                    account.paidSinceBill?.let {
                        Caption("${DisplayFormatters.rupees(it)} paid since the bill came", Modifier.padding(top = Spacing.md), maxLines = 2)
                    }
                    account.unbilled?.let {
                        HorizontalDivider(Modifier.padding(vertical = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Unbilled so far", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            Text(DisplayFormatters.rupees(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Activity for this month, or for the cycle, with its totals.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PillSwitch(listOf("This month", cycleLabel), period, { period = it })
                FintechCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Spent", DisplayFormatters.rupees(activity.spent))
                        Stat(if (account.isCard) "Credited" else "Received", DisplayFormatters.rupees(activity.received))
                        Stat("Payments", activity.messages.size.toString())
                    }
                    Caption(
                        "${dayFormat.format(periodStart)} – ${dayFormat.format(today)}",
                        Modifier.padding(top = Spacing.md)
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
                FintechCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleRow(account.isProvidentFund, androidx.compose.ui.semantics.Role.Switch, onProvidentFund),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
            confirmButton = { AppButton("Remove", { haptics.reject(); removing = false; onRemove() }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Cancel", { removing = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }

    if (naming) {
        EditAccountSheet(account, onSave = { name, bank, kind, balance, network -> onEdit(name, bank, kind, balance, network); naming = false }, onDismiss = { naming = false })
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
                Caption("A code shows up here the moment it arrives.", Modifier.padding(vertical = Spacing.lg), maxLines = 2)
            }
            codes.forEach { message ->
                val otp = message.content as MessageContent.Otp
                CodeCard(
                    message, now, onCopy = { onCopy(message.id, otp.code) },
                    modifier = Modifier.padding(vertical = Spacing.sm)
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
    val bank = remember(account.bankLabel) { MerchantResolver().resolve(account.bankLabel.ifEmpty { account.title }) }
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
        modifier.padding(top = Spacing.sm).semantics { contentDescription = "Pull to reveal net worth, ${(p * 100).toInt()} percent" },
        shape = AppShapes.control, tonalElevation = 3.dp, shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
            Caption(if (p >= 0.85f) "Almost there" else "Pull to reveal net worth")
            androidx.compose.material3.LinearProgressIndicator(
                progress = { p }, modifier = Modifier.padding(top = Spacing.sm).width(160.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        }
    }
}
