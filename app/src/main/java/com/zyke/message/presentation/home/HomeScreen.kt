package com.zyke.message.presentation.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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

/** The three screens the shared home state feeds: the summary, what is due, and the codes. */
enum class HomePage { HOME, BILLS, CODES }

@Composable
private fun HomeContent(state: State, page: HomePage, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit) {
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
            onSetCategory = { id, category -> onIntent(Intent.SetCategory(id, category)) },
            onToggleMute = { onIntent(Intent.ToggleMute(it)) },
            onRename = { id, name -> onIntent(Intent.Rename(id, name)) },
            onSplit = { onIntent(Intent.StartSplit(it)) }
        )
    }

    HomeSheets(state, actions, onIntent, onNavigate)

    when (page) {
        HomePage.HOME -> SummaryPage(state, today, actions, onIntent, onNavigate)
        HomePage.BILLS -> BillsPage(state, today, onIntent)
        HomePage.CODES -> CodesPage(state, onIntent)
    }
}

/**
 * Home answers one question, "am I okay?": the month in one number, then the one thing worth knowing and the
 * next payment due. Everything else has a tab of its own.
 */
@Composable
private fun SummaryPage(state: State, today: LocalDate, actions: FeedActions, onIntent: (Intent) -> Unit, onNavigate: (AppTab) -> Unit) {
    val focus = state.nudges.firstOrNull()
    val next = state.upcoming.firstOrNull()

    CompositionLocalProvider(LocalContentInset provides 24.dp) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        if (state.isBlank) {
            item(key = "blank") {
                PlainSection("Nothing here yet") {
                    Caption("Payments and bills show up here as soon as the bank messages arrive.", maxLines = 3)
                }
            }
        }

        state.thisMonth?.takeIf { !it.isEmpty }?.let { month ->
            item(key = "month") { MonthHeadline(month) { onNavigate(AppTab.MONEY) } }
        }

        if (focus != null) {
            item(key = "focus") {
                FocusCard(focus.text, urgent = focus.kind != NudgeKind.PACE) {
                    focus.messageId?.let { onIntent(Intent.OpenMessage(it)) }
                }
            }
        }

        if (state.attention.isNotEmpty()) {
            item(key = "attention") { AttentionStrip(state.attention, today, actions) }
        }

        if (state.agenda.isNotEmpty()) {
            item(key = "plans") {
                PlainSection("Plans") {
                    state.agenda.take(MAX_PLANS).forEachIndexed { index, agendaItem ->
                        if (index > 0) HorizontalDivider()
                        AgendaRow(agendaItem, today) { onIntent(Intent.OpenMessage(agendaItem.message.id)) }
                    }
                }
            }
        }

        if (next != null) {
            item(key = "next") {
                Column(Modifier.fillMaxWidth()) {
                    Caption("NEXT DUE")
                    UpcomingRow(next, today, onOpen = { onIntent(Intent.OpenMessage(next.message.id)) }) { onIntent(Intent.OpenLink(it)) }
                    LinkButton("All bills", { onNavigate(AppTab.BILLS) })
                }
            }
        }
    }
    }
}

/** Everything due or owed, soonest first, on one plain list. */
@Composable
private fun BillsPage(state: State, today: LocalDate, onIntent: (Intent) -> Unit) {
    val empty = state.upcoming.isEmpty() && state.refunds.isEmpty() &&
        state.owed.isEmpty() && state.subscriptions.isEmpty()

    CompositionLocalProvider(LocalContentInset provides 24.dp) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (empty) {
            item(key = "empty") { PlainSection("Nothing due") { Caption("Bills and auto-debits show up here before they are due.", maxLines = 3) } }
        }
        if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming") {
                PlainSection("Due") {
                    state.upcoming.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        UpcomingRow(item, today, onOpen = { onIntent(Intent.OpenMessage(item.message.id)) }) { onIntent(Intent.OpenLink(it)) }
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

/**
 * Codes laid out like an authenticator app: the code in large grouped digits, the sender above it and a ring on
 * the right that empties as the code runs out. Tap a code to copy it. A code is treated as live for ten minutes,
 * which is how long most services accept one.
 */
@Composable
private fun CodesPage(state: State, onIntent: (Intent) -> Unit) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(CODE_TICK_MS)
        }
    }

    val codes = state.messages.filter {
        it.content is MessageContent.Otp && it.message.date >= nowMillis - CODE_LIST_MS && it.id !in state.userState.archivedIds
    }.take(MAX_CODES)

    if (codes.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No codes right now", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            Caption("A code shows up here the moment it arrives.", maxLines = 2)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(codes, key = { it.id }) { message ->
            val otp = message.content as MessageContent.Otp
            val left = (message.message.date + CODE_LIFE_MS - nowMillis).coerceAtLeast(0)
            CodeRow(
                sender = otp.senderName.ifEmpty { message.merchant.name },
                code = otp.code,
                note = otp.paymentAmount?.let { "Payment of ${DisplayFormatters.rupees(it)}" },
                fractionLeft = left.toFloat() / CODE_LIFE_MS,
                secondsLeft = (left / MILLIS_PER_SECOND).toInt(),
                onCopy = { onIntent(Intent.CopyOtp(message.id, otp.code)) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun CodeRow(sender: String, code: String, note: String?, fractionLeft: Float, secondsLeft: Int, onCopy: () -> Unit) {
    val expired = secondsLeft == 0
    val running = if (fractionLeft > CODE_WARN_FRACTION) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val spoken = if (expired) "expired" else "${secondsLeft / SECONDS_PER_MINUTE} minutes ${secondsLeft % SECONDS_PER_MINUTE} seconds left"
    // Digits are grouped in threes and fours so a code can be read at a glance and typed in without losing the place.
    val grouped = if (code.length in CODE_GROUPABLE) code.chunked(code.length / 2).joinToString(" ") else code

    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Copy code", onClick = onCopy)
            .semantics(mergeDescendants = true) { contentDescription = "$sender code $code, $spoken" }
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Caption(sender)
            Text(
                grouped,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (expired) mutedColor() else MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            if (note != null) Caption(note, Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(track, 0f, 360f, false, style = stroke)
                if (!expired) drawArc(running, -90f, 360f * fractionLeft, false, style = stroke)
            }
            Text(
                if (expired) "\u2013" else "${(secondsLeft + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE}m",
                style = MaterialTheme.typography.labelMedium,
                color = if (expired) mutedColor() else running
            )
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
private const val CODE_LIFE_MS = 10 * 60 * 1000L
private const val CODE_LIST_MS = 60 * 60 * 1000L
private const val CODE_TICK_MS = 1000L
private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val CODE_WARN_FRACTION = 0.2f
private val CODE_GROUPABLE = 4..10 step 2
private const val MAX_CODES = 5
private const val MAX_PLANS = 4

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
    Column(Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "See where it went", onClick = onOpen)) {
        Caption("Spent in ${DisplayFormatters.monthName(java.time.YearMonth.from(month.range.start))}")
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

@Composable
private fun UpcomingRow(item: UpcomingItem, today: LocalDate, onOpen: () -> Unit, onPay: (String) -> Unit) {
    val urgent = DisplayFormatters.isUrgent(item.date, today)
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open details", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantChip(item.message.merchant.logo, imageKey = item.message.merchant.imageKey, name = item.message.merchant.name, description = item.title, size = 44.dp)
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
private fun RecentRow(message: ClassifiedMessage, onOpen: () -> Unit) {
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
private fun FocusCard(text: String, urgent: Boolean, onOpen: () -> Unit) {
    val accent = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth()
            .fullWidthClickable(onClickLabel = "Open details", onClick = onOpen)
            .padding(vertical = 4.dp)
    ) {
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
