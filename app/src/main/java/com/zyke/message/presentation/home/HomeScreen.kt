package com.zyke.message.presentation.home

import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.bills.BillsMath
import com.zyke.message.domain.home.FocusBill
import com.zyke.message.domain.home.FocusCode
import com.zyke.message.domain.home.FocusTrip
import com.zyke.message.domain.home.HomeFocus
import com.zyke.message.domain.home.HomeLayout
import com.zyke.message.domain.home.HomeSection
import com.zyke.message.domain.home.QuickActions
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.motion.itemMotion
import com.zyke.message.presentation.motion.rememberEnterGate
import com.zyke.message.presentation.motion.rememberIsEntering
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.presentation.tour.TourScroll
import com.zyke.message.presentation.tour.tourAnchor
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.dashboard.components.periodName
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.LocalContentInset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableLongStateOf
import com.zyke.message.presentation.common.heading
import com.zyke.message.domain.insights.NudgeKind
import com.zyke.message.domain.insights.RefundItem
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.split.SplitShare
import com.zyke.message.domain.life.AgendaItem
import com.zyke.message.presentation.messages.components.whenLabel
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.plan.PlanActions
import com.zyke.message.domain.plan.PlanKeys
import com.zyke.message.domain.plan.PlanKind
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LinkButton
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import com.zyke.message.presentation.motion.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.domain.dashboard.CategoryTotal
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.AppTab
import com.zyke.message.presentation.common.ErrorState
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
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onNavigate: (AppTab) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    page: HomePage = HomePage.HOME
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val openLink = com.zyke.message.presentation.common.LocalOpenLink.current
    LaunchedEffect(viewModel) {
        // Demo mode reads no messages, so it needs no permission and never asks for it.
        val granted = com.zyke.message.data.demo.DemoMode.active || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
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
                is Effect.OpenLink -> openLink(effect.url)
            }
        }
    }

    HomeScreen(state, viewModel::onIntent, onNavigate, onRequestPermission, modifier, page)
}

@Composable
fun HomeScreen(
    state: State,
    onIntent: (Intent) -> Unit,
    onNavigate: (AppTab) -> Unit,
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
    page: HomePage = HomePage.HOME
) {
    // Remembers that the first entrance has played, across the loading states below, rotation and tab switches.
    val gate = rememberEnterGate()
    Column(modifier.fillMaxSize()) {
        val stage = when {
            state.permissionMissing -> 0
            state.hasError -> 1
            state.isLoading || !state.hasLoaded -> 2
            else -> 3
        }
        // The skeleton fades into the real content instead of being swapped for it.
        CrossfadeContent(stage, Modifier.fillMaxSize(), label = "home-stage") { shown ->
            when (shown) {
                0 -> PermissionGate(onGrant = onRequestPermission)
                1 -> ErrorState(
                    title = "Couldn't read your messages",
                    hint = "Check that SMS access is allowed, then try again.",
                    onRetry = { onIntent(Intent.Retry) }
                )
                2 -> MessageListSkeleton()
                else -> HomeContent(state, page, onIntent, onNavigate, gate)
            }
        }
    }
}

/** The three screens the shared home state feeds: the summary, the accounts, and what is due. */
enum class HomePage { HOME, ACCOUNTS, BILLS }

@Composable
private fun HomeContent(state: State, page: HomePage, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit, gate: EnterGate) {
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val actions = remember(onIntent) {
        FeedActions.None.copy(
            onCopyOtp = { id, code -> onIntent(Intent.CopyOtp(id, code)) },
            onDismissAttention = { onIntent(Intent.DismissAttention(it)) },
            onMarkPaid = { onIntent(Intent.DismissAttention(it, forever = true)) },
            onOpenLink = { onIntent(Intent.OpenLink(it)) },
            onOpenMessage = { onIntent(Intent.OpenMessage(it)) },
            onTogglePin = { onIntent(Intent.TogglePin(it)) },
            onArchive = { onIntent(Intent.Archive(it)) },
            onUnarchive = { onIntent(Intent.Unarchive(it)) },
            onSetCategory = { id, category -> onIntent(Intent.SetCategory(id, category)) },
            onSetMessageCategory = { id, category -> onIntent(Intent.SetMessageCategory(id, category)) },
            onToggleMute = { onIntent(Intent.ToggleMute(it)) },
            onSplit = { onIntent(Intent.StartSplit(it)) }
        )
    }

    HomeSheets(state, actions, onIntent, onNavigate)

    var marketsOpen by rememberSaveable { mutableStateOf(false) }
    // The market-close notification asks for the Market watch page.
    val marketRequested = com.zyke.message.presentation.markets.MarketRequests.pending.value
    androidx.compose.runtime.LaunchedEffect(marketRequested) {
        if (marketRequested) {
            marketsOpen = true
            com.zyke.message.presentation.markets.MarketRequests.pending.value = false
        }
    }
    var entryOpen by rememberSaveable { mutableStateOf(false) }
    var entryRequest by androidx.compose.runtime.remember { mutableStateOf<EntryRequest?>(null) }
    // Asked for from the widget, the quick-settings tile or "Share": open the sheet, pre-filled when there is something to fill.
    val incoming = EntryRequests.pending.value
    androidx.compose.runtime.LaunchedEffect(incoming) {
        if (incoming != null) {
            entryRequest = incoming
            entryOpen = true
            EntryRequests.pending.value = null
        }
    }
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    var affordOpen by rememberSaveable { mutableStateOf(false) }
    var customizeOpen by rememberSaveable { mutableStateOf(false) }
    val layout = rememberHomeLayout()
    // Home's deck: the triage page ("fresh" or "quiet", then the chosen stack or empty), the Auto cleared page and "Make it yours".
    var triageRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var autoClearedOpen by rememberSaveable { mutableStateOf(false) }
    var deckSettingsOpen by rememberSaveable { mutableStateOf(false) }
    // The prices are asked for only while Home is in front, and only if the person switched the strip on.
    if (page == HomePage.HOME) com.zyke.message.presentation.markets.MarketPolling()

    // Home's quick actions: Add entry, and (with on-device AI on) Ask AI above it. They hide while the list scrolls down and come back on the way up.
    val summaryList = androidx.compose.foundation.lazy.rememberLazyListState()
    val askShown = com.zyke.message.presentation.ai.rememberAskHomeVisible()
    var actionsShown by rememberSaveable { mutableStateOf(true) }
    var askOpen by rememberSaveable { mutableStateOf(false) }
    val atTop by remember { derivedStateOf { summaryList.firstVisibleItemIndex == 0 && summaryList.firstVisibleItemScrollOffset == 0 } }
    val scrollWatch = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                actionsShown = QuickActions.shown(actionsShown, available.y, atTop)
                return Offset.Zero
            }
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().nestedScroll(scrollWatch)) {
        when (page) {
            HomePage.HOME -> SummaryPage(state, today, layout, onIntent, onNavigate, gate, summaryList, QuickActions.reservedBottomDp(askShown).dp, onOpenMarkets = { marketsOpen = true }, onOpenReview = { reviewOpen = true },
                onOpenTriage = { quiet, stack -> triageRoute = (if (quiet) "quiet:" else "fresh:") + (stack ?: "") },
                onOpenAutoCleared = { autoClearedOpen = true }, onDeckSettings = { deckSettingsOpen = true },
                onAfford = { affordOpen = true }, onCustomize = { customizeOpen = true })
            HomePage.BILLS -> BillsPage(state, today, onIntent, gate, onAfford = { affordOpen = true })
            HomePage.ACCOUNTS -> AccountsPage(state, onIntent)
        }
        // The quick actions: money that came in no message (Add entry), and a question about your money (Ask AI).
        if (page == HomePage.HOME) {
            HomeQuickActions(
                visible = actionsShown || atTop, extended = QuickActions.extended(atTop), askShown = askShown,
                onAdd = { entryOpen = true }, onAsk = { askOpen = true }, modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
    if (askOpen && askShown) {
        com.zyke.message.presentation.ai.AskMoneySheet(
            state.messages, state.cycle,
            onOpen = { hit -> hit.messageId?.let { onIntent(Intent.OpenMessage(it)) } },
            onDismiss = { askOpen = false }
        )
    }

    if (entryOpen) {
        ManualEntrySheet(
            today,
            onSave = { onIntent(Intent.SaveManualEntry(it)); entryOpen = false; entryRequest = null },
            onDismiss = { entryOpen = false; entryRequest = null },
            initial = entryRequest,
            onAddTrip = { entryOpen = false; entryRequest = null; com.zyke.message.presentation.trains.TripRequests.chooseRequested.value = true }
        )
    }
    if (reviewOpen) {
        val ledger = com.zyke.message.presentation.common.rememberLedgerActions()
        val store = rememberLearnedTemplates()
        val templates by store.templates.collectAsState()
        val taught = remember(templates, state.messages) {
            val counts = state.messages.groupingBy { it.learnedTemplateId }.eachCount()
            templates.map { TaughtFormat(it, counts[it.id] ?: 0) }
        }
        ReviewPage(
            review = state.review,
            onLink = { debit, credit ->
                onIntent(Intent.LinkTransfer(debit, credit))
                ledger.withUndo("Marked as a transfer") { ledger.unlinkTransfer(debit, credit) }
            },
            onNotTransfer = { key ->
                onIntent(Intent.DismissTransfer(key))
                ledger.withUndo("Not a transfer") { ledger.restoreSuggestion(key) }
            },
            onOpen = { onIntent(Intent.OpenMessage(it)) },
            onStillUsing = { onIntent(Intent.RestoreSubscription(it.key)) },
            onBack = { reviewOpen = false },
            onMerge = { primary, duplicate -> onIntent(Intent.MergeDuplicates(primary, duplicate)) },
            onUnmerge = { onIntent(Intent.UnmergeDuplicate(it)) },
            onMarkPaid = { bill ->
                onIntent(Intent.DismissAttention(bill.markedPaidKey, forever = true))
                onIntent(Intent.DismissAttention(bill.attentionKey, forever = true))
            },
            onUndoPaid = { bill ->
                onIntent(Intent.RestoreAttention(bill.markedPaidKey))
                onIntent(Intent.RestoreAttention(bill.attentionKey))
            },
            onTeach = { store.add(it) },
            taught = taught,
            onDeleteTaught = { format ->
                val index = templates.indexOfFirst { it.id == format.template.id }
                store.remove(format.template.id)
                ledger.withUndo("Taught format deleted") { store.add(format.template, index) }
            }
        )
    }

    triageRoute?.let { route ->
        TriageScreen(
            state, startQuiet = route.startsWith("quiet:"), startStack = route.substringAfter(':').takeIf { it.isNotEmpty() },
            onIntent = onIntent, onClose = { triageRoute = null }
        )
    }
    if (autoClearedOpen) {
        val ledger = com.zyke.message.presentation.common.rememberLedgerActions()
        val shown = remember(state.deck, state.triageHidden) { state.shownDeck }
        AutoClearedPage(
            cards = shown.autoCleared,
            onRestore = { card ->
                onIntent(Intent.TriageRestore(card.messageId))
                ledger.withUndo("Restored \u00b7 ${card.headline}") { onIntent(Intent.TriageUnrestore(card.messageId)) }
            },
            onOpen = { onIntent(Intent.PeekMessage(it.messageId)) },
            onBack = { autoClearedOpen = false }
        )
    }
    if (deckSettingsOpen) HomeDeckSettingsPage { deckSettingsOpen = false }
    if (customizeOpen) HomeLayoutPage { customizeOpen = false }
    if (affordOpen) com.zyke.message.presentation.plan.AffordPage(state, onIntent) { affordOpen = false }

    if (marketsOpen) com.zyke.message.presentation.markets.MarketPage(onBack = { marketsOpen = false })
}

/**
 * Home is calm: the balance, the one thing that matters today, today's budget as one line, the deck, the last three payments and a
 * footer. Everything else is behind Customize Home (see [HomeLayout]) or on another tab (Bills, Plan ahead). Six keyed items in the
 * default layout.
 */
@Composable
private fun SummaryPage(
    state: State, today: LocalDate, layout: HomeLayout, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit, gate: EnterGate,
    summaryList: androidx.compose.foundation.lazy.LazyListState, bottomReserve: androidx.compose.ui.unit.Dp, onOpenMarkets: () -> Unit,
    onOpenReview: () -> Unit, onOpenTriage: (quiet: Boolean, stack: String?) -> Unit, onOpenAutoCleared: () -> Unit, onDeckSettings: () -> Unit,
    onAfford: () -> Unit, onCustomize: () -> Unit
) {
    val marketWatchOn = com.zyke.message.presentation.markets.marketsEnabled()
    val deck = remember(state.deck, state.triageHidden) { state.shownDeck }
    val markPaid = rememberMarkPaid(onIntent)
    val markOverduePaid = rememberMarkOverduePaid(onIntent)
    val nudge = state.nudges.firstOrNull()
    val planHandler = rememberPlanActionHandler(onIntent)
    // Deposits maturing within the same two weeks as the rest of the agenda; dismissed or snoozed like any plan item (only when Goals and reminders is on).
    val maturities = if (layout.shows(HomeSection.GOALS)) com.zyke.message.presentation.investments.rememberMaturitiesAhead(state.planActions, today) else emptyList()
    // The clock ticks every second, but only the code card needs to see it: Home itself changes only when a code appears or runs out.
    val now = rememberNowState()
    val otps = remember(state.messages, state.userState.archivedIds) { otpCandidates(state) }
    val codes by remember(otps) { derivedStateOf(structuralEqualityPolicy()) { freshCodes(otps, now.value) } }

    val tourBanner = com.zyke.message.presentation.tour.rememberTourBannerVisible()
    // Flights on their day of travel: worked out here, asked about once each time Home comes to the front.
    val flightsToday = com.zyke.message.presentation.flights.rememberFlightsToday(state.flights, state.userState.dismissedAttention)
    var flightOpen by rememberSaveable { mutableStateOf<String?>(null) }
    // The form for a flight added by hand: "new", or the id of the one being changed.
    var flightEditor by rememberSaveable { mutableStateOf<String?>(null) }
    // A flight notification opens that flight's sheet.
    val requestedFlight = com.zyke.message.presentation.flights.FlightRequests.pending.value
    androidx.compose.runtime.LaunchedEffect(requestedFlight) {
        if (requestedFlight != null) {
            flightOpen = requestedFlight
            com.zyke.message.presentation.flights.FlightRequests.pending.value = null
        }
    }
    // Trains on their day of travel, the same way; and the one "Add a trip" entry that asks flight or train.
    val trainsToday = com.zyke.message.presentation.trains.rememberTrainsToday(state.trains, state.userState.dismissedAttention)
    var trainOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var trainEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var tripChoice by rememberSaveable { mutableStateOf(false) }
    val requestedTrain = com.zyke.message.presentation.trains.TrainRequests.pending.value
    androidx.compose.runtime.LaunchedEffect(requestedTrain) {
        if (requestedTrain != null) {
            trainOpen = requestedTrain
            com.zyke.message.presentation.trains.TrainRequests.pending.value = null
        }
    }
    val chooseTrip = com.zyke.message.presentation.trains.TripRequests.chooseRequested.value
    androidx.compose.runtime.LaunchedEffect(chooseTrip) {
        if (chooseTrip) {
            tripChoice = true
            com.zyke.message.presentation.trains.TripRequests.chooseRequested.value = false
        }
    }
    val addFlight = com.zyke.message.presentation.flights.FlightRequests.addRequested.value
    androidx.compose.runtime.LaunchedEffect(addFlight) {
        if (addFlight) {
            flightEditor = com.zyke.message.presentation.flights.NEW_FLIGHT
            com.zyke.message.presentation.flights.FlightRequests.addRequested.value = false
        }
    }
    CompositionLocalProvider(LocalContentInset provides Spacing.xl) {
    flightOpen?.let { key ->
        com.zyke.message.presentation.flights.FlightSheet(
            key, flightsToday, onDismiss = { flightOpen = null },
            onEdit = { id -> flightOpen = null; flightEditor = id }
        )
    }
    flightEditor?.let { id -> com.zyke.message.presentation.flights.ManualFlightSheetHost(id) { flightEditor = null } }
    trainOpen?.let { key ->
        com.zyke.message.presentation.trains.TrainSheet(
            key, trainsToday, onDismiss = { trainOpen = null },
            onEdit = { id -> trainOpen = null; trainEditor = id }
        )
    }
    trainEditor?.let { id -> com.zyke.message.presentation.trains.ManualTrainSheetHost(id) { trainEditor = null } }
    if (tripChoice) {
        com.zyke.message.presentation.trains.TripChoiceSheet(
            onFlight = { tripChoice = false; flightEditor = com.zyke.message.presentation.flights.NEW_FLIGHT },
            onTrain = { tripChoice = false; trainEditor = com.zyke.message.presentation.trains.NEW_TRAIN },
            onDismiss = { tripChoice = false }
        )
    }

    // What the Today card can show: bills still to pay (upcoming, then overdue), keyed the way the pick names them.
    val billViews = HashMap<String, FocusBillView>()
    state.planUpcoming.filter { it.paidOn == null }.forEach { item ->
        billViews[item.markedPaidKey] = FocusBillView(
            item.title, if (item.kind == UpcomingKind.CARD_BILL) "Bill" else "Auto-debit", item.date, BillsMath.left(item.amount, item.partPaid),
            item.message, item.paymentLink, onMarkPaid = { markPaid(item) }
        )
    }
    state.review.bills.forEach { bill ->
        billViews.putIfAbsent(
            bill.markedPaidKey,
            FocusBillView(bill.label, "Bill", bill.dueDate, bill.amount, bill.message, null, onMarkPaid = { markOverduePaid(bill) })
        )
    }
    val tripFocus = flightsToday.flights.map { FocusTrip(it.key, false, (it.departure?.toSecondOfDay()?.toLong()?.times(1000L)) ?: Long.MAX_VALUE) } +
        trainsToday.trains.map { FocusTrip(it.key, true, (it.departure?.toSecondOfDay()?.toLong()?.times(1000L)) ?: Long.MAX_VALUE) }
    val billFocus = billViews.map { (key, view) -> FocusBill(key, view.date, view.amount) }
    val codeFocus = otps.map { FocusCode(it.id, it.message.date) }
    val pick by remember(tripFocus, billFocus, codeFocus, today) {
        derivedStateOf(structuralEqualityPolicy()) { HomeFocus.pick(now.value, today, tripFocus, codeFocus, billFocus) }
    }
    val onMore: () -> Unit = {
        if (layout.shows(HomeSection.DECK) && deck.toGo > 0) onOpenTriage(false, null) else onNavigate(AppTab.BILLS)
    }
    // The deck is always there while it is switched on (with nothing to do it is one quiet "All caught up" row); the review stays when it is off.
    val showDeckSection = layout.shows(HomeSection.DECK) || state.review.count > 0
    val messagesById = remember(state.messages) { state.messages.associateBy { it.id } }

    TourScroll(TourTab.HOME, summaryList)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = summaryList,
        contentPadding = PaddingValues(start = Spacing.xl, top = Spacing.sm, end = Spacing.xl, bottom = bottomReserve),
        // One rhythm: 16 dp between blocks (24 before the deck and recent activity), 20 dp gutters. The first eight blocks enter in a stagger, once (see enterOnce);
        // a block that comes or goes later fades and the rest close the gap (itemMotion).
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        var slot = 0
        // The Market strip is the very first item, above everything (including the balance): one slim ticker line, nothing else.
        if (layout.shows(HomeSection.MARKET) && marketWatchOn) {
            val at = slot++
            item(key = "market") { Box(itemMotion().enterOnce(gate, at)) { com.zyke.message.presentation.markets.MarketStrip(onOpenMarkets) } }
        }
        if (tourBanner) {
            val at = slot++
            item(key = "tour-banner") { Box(itemMotion().enterOnce(gate, at)) { com.zyke.message.presentation.tour.TourBanner() } }
        }
        if (state.isBlank) {
            // Nothing to show yet: say so gently, and what will appear.
            val at = slot++
            item(key = "blank") {
                com.zyke.message.presentation.common.EmptyState(
                    "Nothing here yet",
                    "Your balances, bills and spending appear here as messages from your bank arrive.",
                    itemMotion().enterOnce(gate, at),
                    icon = R.drawable.ic_wallet_24
                )
            }
        }

        // The one hero: the balance, alone.
        run {
            val at = slot++
            item(key = "balance") {
                val intro = gate.rememberIsEntering(at)
                Box(itemMotion().enterOnce(gate, at).tourAnchor(TourAnchor.HOME_BALANCE)) { HomeBalanceHero(state.accounts, intro = intro) { onNavigate(AppTab.ACCOUNTS) } }
            }
        }

        if (!state.isBlank) {
            val at = slot++
            item(key = "focus") {
                TodayCard(
                    pick = pick, today = today, codes = codes, nowMillis = now.value, flights = flightsToday, trains = trainsToday, bills = billViews, nudge = nudge,
                    onCopy = { message, code -> onIntent(Intent.CopyOtp(message.id, code)) },
                    onAllCodes = { onIntent(Intent.OpenCodes) },
                    onOpenFlight = { flightOpen = it }, onOpenTrain = { trainOpen = it },
                    onDismissTrip = { onIntent(Intent.DismissAttention(it, forever = true)) },
                    onAddTrip = { tripChoice = true },
                    onPay = { onIntent(Intent.OpenLink(it)) },
                    onOpenMessage = { onIntent(Intent.OpenMessage(it)) },
                    onMore = onMore,
                    modifier = itemMotion().enterOnce(gate, at).tourAnchor(TourAnchor.HOME_TODAY)
                )
            }
        }

        if (layout.shows(HomeSection.BUDGET) && !state.isBlank) {
            val at = slot++
            item(key = "today") {
                val intro = gate.rememberIsEntering(at)
                TodayBudgetRow(
                    state.daily, onOpen = { onIntent(Intent.OpenCalendar) }, onAdjust = { onIntent(Intent.OpenAdjustDaily) }, intro = intro,
                    modifier = itemMotion().enterOnce(gate, at).tourAnchor(TourAnchor.HOME_BUDGET)
                )
            }
        }

        // The deck: what is new, sorted into a few stacks. The review row sits under it, and stays when the deck is switched off.
        if (showDeckSection) {
            val at = slot
            slot += deckSlots(deck)
            item(key = "deck") {
                val intro = gate.rememberIsEntering(at)
                Box(itemMotion().padding(top = Spacing.sm).tourAnchor(TourAnchor.HOME_DECK)) {
                    DeckSection(
                        deck, messagesById, gate, at, intro, showDeck = layout.shows(HomeSection.DECK), reviewCount = state.review.count,
                        onIntent = onIntent, onOpen = onOpenTriage, onOpenAutoCleared = onOpenAutoCleared, onOpenReview = onOpenReview, onSettings = onDeckSettings
                    )
                }
            }
        }

        if (layout.shows(HomeSection.SHORTCUTS)) {
            val at = slot++
            item(key = "plan-tiles") {
                // Four shortcuts in two rows of two, each a page of its own.
                Column(itemMotion().enterOnce(gate, at), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ShortcutTile("Calendar", R.drawable.baseline_event_24, Modifier.weight(1f)) { onIntent(Intent.OpenAgenda) }
                        ShortcutTile("Goals", R.drawable.ic_goal_24, Modifier.weight(1f)) { onIntent(Intent.OpenGoals) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ShortcutTile("Reminders", R.drawable.ic_reminder_24, Modifier.weight(1f)) { onIntent(Intent.OpenReminders) }
                        ShortcutTile("Can I afford it?", R.drawable.ic_afford_24, Modifier.weight(1f)) { onAfford() }
                    }
                }
            }
        }

        if (layout.shows(HomeSection.GOALS) && (state.planAgenda.isNotEmpty() || maturities.isNotEmpty())) {
            val at = slot++
            item(key = "plans") {
                FintechCard(itemMotion().enterOnce(gate, at)) {
                    CardHeader("Plans")
                    maturities.forEachIndexed { index, maturity ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        val open = { com.zyke.message.presentation.investments.InvestmentRequests.pending.value = com.zyke.message.presentation.investments.InvestmentRequest(maturity.holding.id) }
                        PlanItemFrame(maturity.holding.name, com.zyke.message.domain.plan.PlanKeys.maturity(maturity), PlanKind.AGENDA, today, planHandler, onOpen = open) { more ->
                            com.zyke.message.presentation.investments.MaturityRow(maturity, open, more)
                        }
                    }
                    state.planAgenda.take(MAX_PLANS).forEachIndexed { index, agendaItem ->
                        if (index > 0 || maturities.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        // A trip opens as its own scene; anything else shows the message.
                        val open = { onIntent(if (agendaItem.kind == LifeKind.TRAVEL) Intent.OpenJourney(agendaItem.message.id) else Intent.OpenMessage(agendaItem.message.id)) }
                        val remindOn = agendaItem.date ?: today.plusDays(1)
                        val hasReminder = state.reminderList.any { it.title == agendaItem.title && it.date == remindOn }
                        val extras = if (hasReminder) emptyList() else listOf(
                            PlanExtraAction("Remind", "Remind me about ${agendaItem.title}") {
                                val reminder = Reminder(java.util.UUID.randomUUID().toString(), agendaItem.title, null, remindOn)
                                onIntent(Intent.SaveReminder(reminder))
                                planHandler.announce("Reminder set for ${agendaItem.title}") { onIntent(Intent.RemoveReminder(reminder.id)) }
                            }
                        )
                        PlanItemFrame(agendaItem.title, PlanKeys.agenda(agendaItem), PlanKind.AGENDA, today, planHandler, onOpen = open, extras = extras) { more ->
                            AgendaRow(agendaItem, today, open, more)
                        }
                    }
                }
            }
        }

        if (layout.shows(HomeSection.QUICK_ADDS) && state.quickAdds.isNotEmpty()) {
            val at = slot++
            item(key = "quickadds") { FintechCard(itemMotion().enterOnce(gate, at)) { QuickAddsPrompt(state.quickAdds.size) { onIntent(Intent.OpenQuickAdds) } } }
        }

        if (layout.shows(HomeSection.ACTIVITY) && state.recent.isNotEmpty()) {
            val at = slot++
            item(key = "activity") {
                RecentActivity(
                    state.recent.take(MAX_RECENT_ACTIVITY), onOpen = { onIntent(Intent.OpenMessage(it)) }, onSeeAll = { onNavigate(AppTab.INBOX) },
                    modifier = itemMotion().padding(top = Spacing.sm).enterOnce(gate, at)
                )
            }
        }

        item(key = "footer") { HomeFooter(onCustomize, Modifier.tourAnchor(TourAnchor.HOME_CUSTOMIZE)) }
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
            onDismiss = { onIntent(Intent.CloseMessage) },
            related = remember(detail.id, state.messages) { com.zyke.message.domain.ledger.PaymentGroups.of(state.messages).followersOf(detail.id) }
        )
    }

    val account = state.accounts.accounts.firstOrNull { it.key == state.openAccountKey }
    if (account != null) {
        AccountSheet(
            account = account,
            state = state,
            onPay = { onIntent(Intent.OpenLink(it)) },
            onEdit = { name, bank, kind, balance, network -> onIntent(Intent.EditAccount(account.key, name, bank, kind, balance, network)) },
            onStatementDay = { onIntent(Intent.SetStatementDay(account.key, it)) },
            onRemove = { onIntent(Intent.RemoveAccount(account.key)) },
            onProvidentFund = { onIntent(Intent.SetProvidentFund(account.key, it)) },
            onOpenMessage = { onIntent(Intent.OpenMessage(it)) },
            onDismiss = { onIntent(Intent.CloseAccount) }
        )
    }

    if (state.showGoals) {
        PlanPage("Goals", { onIntent(Intent.CloseGoals) }) {
            GoalsContent(
                goals = state.goals,
                onSave = { onIntent(Intent.SaveGoal(it)) },
                onRemove = { onIntent(Intent.RemoveGoal(it)) }
            )
        }
    }

    if (state.showAgenda) {
        CalendarPage(
            messages = state.messages, reminders = state.reminderList, events = state.events, goals = state.goalList, plan = state.plan,
            now = state.now,
            onSaveEvent = { onIntent(Intent.SaveEvent(it)) },
            onRemoveEvent = { onIntent(Intent.RemoveEvent(it)) },
            onOpenMessage = { onIntent(Intent.OpenMessage(it)) },
            onDismiss = { onIntent(Intent.CloseAgenda) }
        )
    }

    if (state.showSubscriptions) SubscriptionsPage(state, onIntent) { onIntent(Intent.CloseSubscriptions) }

    if (state.showReminders) {
        PlanPage("Reminders", { onIntent(Intent.CloseReminders) }) {
            RemindersContent(
                reminders = state.reminders, today = LocalDate.ofInstant(state.now, ZoneId.systemDefault()),
                onSave = { onIntent(Intent.SaveReminder(it)) }, onRemove = { onIntent(Intent.RemoveReminder(it)) }
            )
        }
    }

    state.messages.firstOrNull { it.id == state.journeyMessageId }?.let { trip ->
        JourneySheet(
            message = trip,
            onOpenMessage = { onIntent(Intent.CloseJourney); onIntent(Intent.OpenMessage(trip.id)) },
            onDismiss = { onIntent(Intent.CloseJourney) }
        )
    }

    if (state.showCodes) {
        CodesSheet(state, onCopy = { id, code -> onIntent(Intent.CopyOtp(id, code)) }, onDismiss = { onIntent(Intent.CloseCodes) })
    }

    // The scope outlives the sheet, so the Undo snackbar still shows once the sheet has closed.
    val dailySnackbar = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val dailyScope = androidx.compose.runtime.rememberCoroutineScope()
    if (state.adjustingDaily) {
        val before = state.dailyOverride
        val undo: (String) -> Unit = { message ->
            dailyScope.launch {
                val result = dailySnackbar.showSnackbar(message, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    onIntent(if (before != null) Intent.SetDailyBudget(before) else Intent.ResetDailyBudget)
                }
            }
        }
        AdjustDailySheet(
            current = state.daily, suggestion = state.dailySuggestion,
            onSave = { onIntent(Intent.SetDailyBudget(it)); undo("Daily budget changed") },
            onReset = { onIntent(Intent.ResetDailyBudget); undo("Back to the suggested daily budget") },
            onDismiss = { onIntent(Intent.CloseAdjustDaily) }
        )
    }

    val calendarOffset = state.calendarOffset
    val daily = state.daily
    if (calendarOffset != null && daily != null) {
        BudgetCalendarSheet(
            messages = state.messages, daily = daily, cycle = state.cycle, planned = state.plan.items, offset = calendarOffset, now = state.now,
            onShift = { onIntent(Intent.ShiftCalendar(it)) }, onDismiss = { onIntent(Intent.CloseCalendar) }
        )
    }

    if (state.showQuickAdds) {
        QuickAddSheet(
            items = state.quickAdds,
            onAdd = { onIntent(Intent.AddQuick(it)) },
            onDismissItem = { onIntent(Intent.DismissQuick(it)) },
            onDismiss = { onIntent(Intent.CloseQuickAdds) }
        )
    }

    state.splitting?.let { message ->
        SplitDialog(
            message = message,
            onSave = { onIntent(Intent.SaveSplit(it)) },
            onDismiss = { onIntent(Intent.CancelSplit) }
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
internal const val SECONDS_PER_MINUTE = 60
internal const val CODE_WARN_FRACTION = 0.2f
internal val CODE_GROUPABLE = 4..10 step 2
private const val MAX_PLANS = 4
private const val REMINDER_SOON_DAYS = 7L
private const val MAX_RECENT_ACTIVITY = 3

/** One thing ahead: an order on its way, a trip, an appointment or a renewal. Tap for the message. */
@Composable
private fun AgendaRow(item: AgendaItem, today: LocalDate, onOpen: () -> Unit, more: (@Composable () -> Unit)? = null) {
    val life = item.message.content as MessageContent.Life
    val date = item.date
    val soon = date != null && date <= today.plusDays(2)
    PlanRow(
        title = item.title,
        modifier = Modifier.fullWidthClickable(onClickLabel = "Open details", onClick = onOpen),
        leading = {
            MerchantChip(
                item.message.merchant.logo, imageKey = item.message.merchant.imageKey, name = item.message.merchant.name, originalName = item.message.merchant.originalName,
                description = item.title, size = 44.dp
            )
        },
        caption = { Caption("${item.kind.label} \u00B7 ${item.status}", maxLines = 2) },
        amount = {
            Text(
                text = date?.let { whenLabel(it, life, today) } ?: "On the way",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (soon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                maxLines = 1
            )
        },
        more = more
    )
}

/** The person says it is paid: the bill goes from the list and the attention strip, and a tap on Undo brings it back. */
@Composable
internal fun rememberMarkPaid(onIntent: (Intent) -> Unit, tickOnPay: Boolean = true): (UpcomingItem) -> Unit {
    val snackbar = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val paidBills = rememberPaidBills()
    val tick = com.zyke.message.presentation.common.rememberTick()
    return { item ->
        if (tickOnPay) tick()
        paidBills.record(com.zyke.message.domain.home.PaidBill.from(item, LocalDate.now()))
        onIntent(Intent.DismissAttention(item.markedPaidKey, forever = true))
        item.attentionKey?.let { onIntent(Intent.DismissAttention(it, forever = true)) }
        scope.launch {
            val result = snackbar.showSnackbar("Marked ${item.title} as paid", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                tick()
                onIntent(Intent.RestoreAttention(item.markedPaidKey))
                item.attentionKey?.let { onIntent(Intent.RestoreAttention(it)) }
                paidBills.remove(item.markedPaidKey)
            }
        }
    }
}

/** The person says an overdue bill was paid: it leaves Home's Today card and the review list, and Undo brings it back. */
@Composable
internal fun rememberMarkOverduePaid(onIntent: (Intent) -> Unit): (com.zyke.message.domain.ledger.OverdueBill) -> Unit {
    val snackbar = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tick = com.zyke.message.presentation.common.rememberTick()
    return { bill ->
        tick()
        onIntent(Intent.DismissAttention(bill.markedPaidKey, forever = true))
        onIntent(Intent.DismissAttention(bill.attentionKey, forever = true))
        scope.launch {
            val result = snackbar.showSnackbar("Marked ${bill.label} as paid", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                tick()
                onIntent(Intent.RestoreAttention(bill.markedPaidKey))
                onIntent(Intent.RestoreAttention(bill.attentionKey))
            }
        }
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

    Column(Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp)) {
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
internal fun RecentRow(message: ClassifiedMessage, onOpen: () -> Unit) {
    val tx = message.content as? MessageContent.Transaction ?: return
    val sign = if (tx.isReceived) "+" else "-"
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantChip(message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name, originalName = message.merchant.originalName, description = message.merchant.name, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(message.merchant.displayName)
            com.zyke.message.presentation.messages.components.PaymentInstrumentCaption(
                instrument = com.zyke.message.presentation.messages.components.rememberInstrument(message),
                last4 = tx.accountNumber,
                rest = DisplayFormatters.listStamp(message.message.date)
            )
        }
        Spacer(Modifier.width(12.dp))
        AmountText(
            "$sign ${DisplayFormatters.rupees(tx.amount)}",
            if (tx.isReceived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
    }
}
