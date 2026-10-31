package com.zyke.message.presentation.home

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
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.common.heading
import com.zyke.message.domain.insights.NudgeKind
import com.zyke.message.domain.insights.RefundItem
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.split.SplitShare
import com.zyke.message.domain.life.AgendaItem
import com.zyke.message.presentation.messages.components.whenLabel
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
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
    modifier: Modifier = Modifier,
    page: HomePage = HomePage.HOME
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val openLink = com.zyke.message.presentation.common.LocalOpenLink.current
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
                else -> HomeContent(state, page, onIntent, onNavigate)
            }
        }
    }
}

/** The three screens the shared home state feeds: the summary, the accounts, and what is due. */
enum class HomePage { HOME, ACCOUNTS, BILLS }

@Composable
private fun HomeContent(state: State, page: HomePage, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit) {
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
            onRename = { id, name, onlyThis -> onIntent(Intent.Rename(id, name, onlyThis)) },
            onSplit = { onIntent(Intent.StartSplit(it)) }
        )
    }

    HomeSheets(state, actions, onIntent, onNavigate)

    var marketsOpen by rememberSaveable { mutableStateOf(false) }
    var entryOpen by rememberSaveable { mutableStateOf(false) }
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    // The prices are asked for only while Home is in front, and only if the person switched the strip on.
    if (page == HomePage.HOME) com.zyke.message.presentation.markets.MarketPolling()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        when (page) {
            HomePage.HOME -> SummaryPage(state, today, actions, onIntent, onNavigate, onOpenMarkets = { marketsOpen = true }, onOpenReview = { reviewOpen = true })
            HomePage.BILLS -> BillsPage(state, today, onIntent)
            HomePage.ACCOUNTS -> AccountsPage(state, onIntent)
        }
        // The one prominent add action: money that came in no message.
        if (page == HomePage.HOME) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { entryOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Text("+ Entry", fontWeight = FontWeight.SemiBold) }
        }
    }

    if (entryOpen) ManualEntrySheet(today, onSave = { onIntent(Intent.SaveManualEntry(it)); entryOpen = false }, onDismiss = { entryOpen = false })
    if (reviewOpen) {
        ReviewPage(
            review = state.review,
            onLink = { debit, credit -> onIntent(Intent.LinkTransfer(debit, credit)) },
            onNotTransfer = { onIntent(Intent.DismissTransfer(it)) },
            onOpen = { onIntent(Intent.OpenMessage(it)) },
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
            }
        )
    }

    if (marketsOpen) com.zyke.message.presentation.markets.MarketPage(onBack = { marketsOpen = false })
}

/**
 * Home is the money at a glance: what the accounts hold, today's budget, a code if one has just arrived, the one
 * thing worth knowing, what is due next and the latest activity. Each part is a card; everything else has a tab.
 */
@Composable
private fun SummaryPage(
    state: State, today: LocalDate, actions: FeedActions, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit, onOpenMarkets: () -> Unit,
    onOpenReview: () -> Unit
) {
    val showMarkets = com.zyke.message.presentation.markets.marketsEnabled()
    val markPaid = rememberMarkPaid(onIntent)
    val focus = state.nudges.firstOrNull()
    val next = state.upcoming.firstOrNull()
    val nowMillis = rememberNowMillis()
    val codes = freshCodes(state, nowMillis)

    CompositionLocalProvider(LocalContentInset provides 16.dp) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showMarkets) {
            item(key = "market") { com.zyke.message.presentation.markets.MarketStrip(onOpenMarkets) }
        }

        if (state.isBlank) {
            item(key = "blank") {
                com.zyke.message.presentation.common.EmptyState(
                    "Nothing here yet",
                    "Your balances, bills and spending appear here as messages from your bank arrive."
                )
            }
        }

        item(key = "balance") { BalanceHero(state.accounts) { onNavigate(AppTab.ACCOUNTS) } }

        state.daily?.let { daily ->
            item(key = "today") { FintechCard(onClick = { onIntent(Intent.OpenCalendar) }) { TodayBudget(daily) } }
        }

        // Codes and what needs attention are the two things that cannot wait, so they share one card, and the one outline.
        val attention = state.attention.filterNot { it is AttentionItem.FreshOtp }
        if (codes.isNotEmpty() || attention.isNotEmpty()) {
            item(key = "priority") {
                FintechCard(movingOutline = true) {
                    if (codes.isNotEmpty()) {
                        CodePagerContent(
                            codes, nowMillis,
                            onCopy = { message, code -> onIntent(Intent.CopyOtp(message.id, code)) },
                            onAll = { onIntent(Intent.OpenCodes) }
                        )
                    }
                    if (codes.isNotEmpty() && attention.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    }
                    if (attention.isNotEmpty()) AttentionStrip(attention, today, actions, inset = 0.dp, bordered = false)
                }
            }
        }

        if (state.review.count > 0) {
            item(key = "review") { ReviewCard(state.review.count, onOpenReview) }
        }

        if (focus != null) {
            item(key = "focus") {
                FintechCard(onClick = focus.messageId?.let { id -> { onIntent(Intent.OpenMessage(id)) } }) {
                    FocusCard(focus.text, urgent = focus.kind != NudgeKind.PACE)
                }
            }
        }

        if (next != null) {
            item(key = "next") {
                FintechCard {
                    Caption("NEXT DUE")
                    UpcomingRow(next, today, onOpen = { onIntent(Intent.OpenMessage(next.message.id)) }, onMarkPaid = markPaid) { onIntent(Intent.OpenLink(it)) }
                    LinkButton("All bills", { onNavigate(AppTab.BILLS) })
                }
            }
        }

        item(key = "plan-tiles") {
            // Three squares in a line, each a page of its own.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortcutTile("Calendar", R.drawable.baseline_event_24, Modifier.weight(1f)) { onIntent(Intent.OpenAgenda) }
                ShortcutTile("Goals", R.drawable.ic_goal_24, Modifier.weight(1f)) { onIntent(Intent.OpenGoals) }
                ShortcutTile("Reminders", R.drawable.ic_reminder_24, Modifier.weight(1f)) { onIntent(Intent.OpenReminders) }
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "activity") {
                FintechCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowTitle("Recent activity", Modifier.weight(1f))
                        state.thisMonth?.takeIf { !it.isEmpty }?.let { month ->
                            Caption("Spent ${DisplayFormatters.rupees(month.total)} · ${periodName(month.range)}")
                        }
                    }
                    state.recent.take(MAX_RECENT_ACTIVITY).forEachIndexed { index, message ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        RecentRow(message, onOpen = { onIntent(Intent.OpenMessage(message.id)) })
                    }
                    LinkButton("Insights", { onNavigate(AppTab.MONEY) })
                }
            }
        }

        if (state.agenda.isNotEmpty()) {
            item(key = "plans") {
                FintechCard {
                    RowTitle("Plans")
                    state.agenda.take(MAX_PLANS).forEachIndexed { index, agendaItem ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        AgendaRow(agendaItem, today) {
                            // A trip opens as its own scene; anything else shows the message.
                            onIntent(if (agendaItem.kind == LifeKind.TRAVEL) Intent.OpenJourney(agendaItem.message.id) else Intent.OpenMessage(agendaItem.message.id))
                        }
                    }
                }
            }
        }

        if (state.quickAdds.isNotEmpty()) {
            item(key = "quickadds") { FintechCard { QuickAddsPrompt(state.quickAdds.size) { onIntent(Intent.OpenQuickAdds) } } }
        }
    }
    }
}

private const val REMINDERS_SHOWN = 4

/** Everything due or owed, soonest first, on one plain list. */
@Composable
private fun BillsPage(state: State, today: LocalDate, onIntent: (Intent) -> Unit) {
    val markPaid = rememberMarkPaid(onIntent)
    CompositionLocalProvider(LocalContentInset provides 16.dp) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.upcoming.isEmpty()) {
            item(key = "no-bills") {
                FintechCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("\u2713", style = MaterialTheme.typography.headlineMedium, color = com.zyke.message.presentation.common.limitColors().good, fontWeight = FontWeight.Bold)
                        Text(
                            "No bills currently available", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Caption(
                            "Nothing is due in the next 30 days. Card bills and auto-debits show up here when their messages arrive.",
                            Modifier.padding(top = 4.dp), maxLines = 4, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
        // Your own reminders belong with what is coming due, so the page is not bare when no bill is.
        if (state.reminders.isNotEmpty()) {
            item(key = "reminders") {
                PlainSection("Your reminders") {
                    state.reminders.take(REMINDERS_SHOWN).forEachIndexed { index, due ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open reminders") { onIntent(Intent.OpenReminders) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                RowTitle(due.reminder.title)
                                Caption(DisplayFormatters.dueLabel(due.on, today).replaceFirst("Due ", "").replaceFirstChar { it.uppercase() } + if (due.reminder.repeatsMonthly) " \u00B7 every month" else "")
                            }
                            due.reminder.amount?.let { AmountText(DisplayFormatters.rupees(it), MaterialTheme.colorScheme.secondary) }
                        }
                    }
                    if (state.reminders.size > REMINDERS_SHOWN) LinkButton("All reminders", { onIntent(Intent.OpenReminders) })
                }
            }
        }
        if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming") {
                PlainSection("Due") {
                    state.upcoming.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        UpcomingRow(item, today, onOpen = { onIntent(Intent.OpenMessage(item.message.id)) }, onMarkPaid = markPaid) { onIntent(Intent.OpenLink(it)) }
                    }
                }
            }
        }
        if (state.refunds.isNotEmpty()) {
            item(key = "refunds") {
                PlainSection("Refunds") {
                    state.refunds.forEachIndexed { index, refund ->
                        if (index > 0) HorizontalDivider()
                        RefundRow(refund) { onIntent(Intent.OpenMessage(refund.message.id)) }
                    }
                }
            }
        }
        if (state.owed.isNotEmpty()) {
            item(key = "owed") {
                PlainSection("Friends owe you") {
                    state.owed.forEachIndexed { index, share ->
                        if (index > 0) HorizontalDivider()
                        OwedRow(share, onSettle = { onIntent(Intent.SettleShare(share.split.id, share.person.name)) }) {
                            onIntent(Intent.OpenMessage(share.split.messageId))
                        }
                    }
                }
            }
        }
        if (state.subscriptions.isNotEmpty()) {
            item(key = "subscriptions") {
                PlainSection("Repeats monthly") {
                    state.subscriptions.forEachIndexed { index, subscription ->
                        if (index > 0) HorizontalDivider()
                        SubscriptionRow(subscription, today) { onIntent(Intent.OpenMessage(subscription.messageId)) }
                    }
                }
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

    val account = state.accounts.accounts.firstOrNull { it.key == state.openAccountKey }
    if (account != null) {
        AccountSheet(
            account = account,
            state = state,
            onPay = { onIntent(Intent.OpenLink(it)) },
            onName = { onIntent(Intent.NameAccount(account.key, it)) },
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
                onRemove = { onIntent(Intent.RemoveGoal(it)) },
                onAdd = { id, amount -> onIntent(Intent.AddToGoal(id, amount)) }
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
private const val MAX_RECENT_ACTIVITY = 5

/** A titled card with its rows. */
@Composable
private fun PlainSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    FintechCard {
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
    Column(Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "See where it went", onClick = onOpen)) {
        Caption("Spent · ${periodName(month.range)}")
        Text(
            text = rememberAnimatedRupees(month.total),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        note?.let { Caption(it, Modifier.padding(top = 2.dp), maxLines = 2) }
    }
}

/** One thing ahead: an order on its way, a trip, an appointment or a renewal. Tap for the message. */
@Composable
private fun AgendaRow(item: AgendaItem, today: LocalDate, onOpen: () -> Unit) {
    val life = item.message.content as MessageContent.Life
    val date = item.date
    val soon = date != null && date <= today.plusDays(2)
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantChip(
            item.message.merchant.logo, imageKey = item.message.merchant.imageKey, name = item.message.merchant.name,
            description = item.title, size = 44.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(item.title)
            Caption("${item.kind.label} · ${item.status}")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = date?.let { whenLabel(it, life, today) } ?: "On the way",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (soon) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            maxLines = 1
        )
    }
}

/** The person says it is paid: the bill goes from the list and the attention strip, and a tap on Undo brings it back. */
@Composable
private fun rememberMarkPaid(onIntent: (Intent) -> Unit): (UpcomingItem) -> Unit {
    val snackbar = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return { item ->
        onIntent(Intent.DismissAttention(item.markedPaidKey, forever = true))
        item.attentionKey?.let { onIntent(Intent.DismissAttention(it, forever = true)) }
        scope.launch {
            val result = snackbar.showSnackbar("Marked ${item.title} as paid", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                onIntent(Intent.RestoreAttention(item.markedPaidKey))
                item.attentionKey?.let { onIntent(Intent.RestoreAttention(it)) }
            }
        }
    }
}

@Composable
private fun UpcomingRow(item: UpcomingItem, today: LocalDate, onOpen: () -> Unit, onMarkPaid: (UpcomingItem) -> Unit, onPay: (String) -> Unit) {
    val paid = item.paidOn != null
    val urgent = !paid && DisplayFormatters.isUrgent(item.date, today)
    val good = com.zyke.message.presentation.common.limitColors().good
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantChip(item.message.merchant.logo, imageKey = item.message.merchant.imageKey, name = item.message.merchant.name, description = item.title, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(item.title)
            if (paid) {
                // Paid today: the day shows it done. Tomorrow the row is gone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\u2713", color = good, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Paid today",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = good
                    )
                }
            } else {
                Caption(
                    (if (item.kind == UpcomingKind.CARD_BILL) "Bill · " else "Auto-debit · ") + DisplayFormatters.dueLabel(item.date, today),
                    color = if (urgent) MaterialTheme.colorScheme.error else mutedColor()
                )
            }
            if (!paid) {
                // Paid some other way, with no message about it: say so, and it goes.
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.paymentLink?.let { link ->
                        AppButton(text = "Pay bill", onClick = { onPay(link) }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                    }
                    AppButton(text = "Mark as paid", onClick = { onMarkPaid(item) }, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        AmountText(DisplayFormatters.rupees(item.paidAmount ?: item.amount), if (paid) good else MaterialTheme.colorScheme.secondary)
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
        MerchantChip(message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name, description = message.merchant.name, size = 44.dp)
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

@Composable
private fun RefundRow(item: RefundItem, onOpen: () -> Unit) {
    val status = if (item.isPending) {
        "On the way since " + shortDate(item.startedOn)
    } else {
        "Arrived " + shortDate(item.arrivedOn!!)
    }
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(item.from)
            Caption(status, color = if (item.isPending) mutedColor() else MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        AmountText(DisplayFormatters.rupees(item.amount), MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun OwedRow(share: SplitShare, onSettle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(share.person.name)
            Caption("For ${share.split.title}")
            AppButton(
                text = "Mark as paid",
                onClick = onSettle,
                style = ButtonStyle.TONAL,
                size = ButtonSize.COMPACT,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        AmountText(DisplayFormatters.rupees(share.person.share), MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun SubscriptionRow(item: Subscription, today: LocalDate, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(item.payee)
            Caption(DisplayFormatters.dueLabel(item.nextDue, today) + " · paid ${item.times} months running")
        }
        Spacer(Modifier.width(12.dp))
        AmountText(DisplayFormatters.rupees(item.amount), MaterialTheme.colorScheme.secondary)
    }
}

private fun shortDate(date: LocalDate): String = java.time.format.DateTimeFormatter.ofPattern("d MMM").format(date)

/** The one thing to read first, set apart by space and a single colour rather than a box of detail. */
@Composable
private fun FocusCard(text: String, urgent: Boolean) {
    val accent = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Caption("FIRST THING", color = accent)
        Text(
            text,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** A title and a short summary on one line; the same folded section the other tabs use. */
@Composable
private fun FoldSection(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) =
    SectionCard(title, summary = summary, content = content)
