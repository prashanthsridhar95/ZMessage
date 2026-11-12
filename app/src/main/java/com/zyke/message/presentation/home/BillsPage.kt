package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.zyke.message.domain.bills.BillsMath
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.insights.RecurringItem
import com.zyke.message.domain.insights.RecurringListItem
import com.zyke.message.domain.insights.RefundItem
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.plan.PlanActions
import com.zyke.message.domain.plan.PlanKeys
import com.zyke.message.domain.plan.PlanKind
import com.zyke.message.domain.split.SplitShare
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.common.LocalContentInset
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowAmount
import com.zyke.message.presentation.messages.components.RowTile
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.AnimatedColumnItems
import com.zyke.message.presentation.motion.AppearVisibility
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.motion.itemMotion
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberIsEntering
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.tour.TourScroll
import com.zyke.message.presentation.tour.tourAnchor
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REMINDERS_SHOWN = 4

/** One line of the Paid group: a bill marked as paid, or one the messages say was paid today. */
private class PaidLine(val key: String, val title: String, val caption: String, val amount: BigDecimal, val restore: (() -> Unit)?)

/**
 * What is due or owed, soonest first. The hero is this month's bills (what is left to pay, a bar for what is paid); under it
 * your reminders, the Due card with its Paid group, refunds, what friends owe and what repeats. Marking a bill paid plays one
 * short beat on its row (the chip turns to "Paid" with a check), then the row moves to the Paid group and the hero updates;
 * Undo reverses all of it.
 */
@Composable
internal fun BillsPage(state: State, today: LocalDate, onIntent: (Intent) -> Unit, gate: EnterGate) {
    val markPaid = rememberMarkPaid(onIntent, tickOnPay = false)
    val haptics = rememberHaptics()
    val motion = rememberMotion()
    val scope = rememberCoroutineScope()
    val planHandler = rememberPlanActionHandler(onIntent)
    val merge = rememberMergeActions(state, onIntent)
    val group = rememberGroupActions(state, onIntent)
    val paidRepository = rememberPaidBills()
    val paidBills by paidRepository.bills.collectAsState()
    // Entries and groups, already arranged by the view model; a group only folds rows, each entry still counts on its own.
    val recurringArranged = state.recurringItems
    val billsList = rememberLazyListState()
    TourScroll(TourTab.BILLS, billsList)

    val upcoming = state.upcoming
    val open = remember(upcoming) { BillsMath.sortDue(upcoming.filter { it.paidOn == null }, { it.date }, { it.amount }, { it.title }) }
    val paidToday = remember(upcoming) { upcoming.filter { it.paidOn != null } }
    val paidMarked = remember(paidBills, today) {
        BillsMath.sortPaid(paidBills.filter { BillsMath.paidThisMonth(it.markedOn, today) }, { it.markedOn }, { it.title })
    }
    val summary = remember(open, paidToday, paidMarked, today) {
        val due = open.filter { BillsMath.countsForThisMonth(it.date, today) }
        val paid = paidToday.filter { BillsMath.countsForThisMonth(it.date, today) }.map { it.paidAmount ?: it.amount } +
            paidMarked.filter { BillsMath.countsForThisMonth(it.dueDate, today) }.map { it.amount }
        BillsMath.summary(
            due = due.map { BillsMath.left(it.amount, it.partPaid) },
            paid = paid,
            partPaid = due.fold(BigDecimal.ZERO) { sum, it -> sum + (it.partPaid ?: BigDecimal.ZERO) }
        )
    }

    // Bills just marked paid: their rows already read as paid for the beat before they move. Cleared once the bill has left.
    var settling by remember { mutableStateOf(emptySet<String>()) }
    val openKeys = remember(open) { open.map { it.markedPaidKey }.toSet() }
    LaunchedEffect(openKeys) { if (settling.isNotEmpty()) settling = settling.filterTo(HashSet()) { it in openKeys } }
    val onMarkPaid: (UpcomingItem) -> Unit = { item ->
        val key = item.markedPaidKey
        if (key !in settling) {
            settling = settling + key
            haptics.tick()
            scope.launch {
                try {
                    if (!motion.reduced) delay(Motion.STANDARD_MS.toLong())
                } finally {
                    markPaid(item)
                }
            }
        }
    }

    val dayMonth = remember { FormatCache.pattern("d MMM", Locale.getDefault()) }
    val paidLines = remember(paidToday, paidMarked, today, onIntent, paidRepository) {
        paidToday.map { PaidLine("today-${it.markedPaidKey}", it.title, "Paid today", it.paidAmount ?: it.amount, null) } +
            paidMarked.map { bill ->
                PaidLine(
                    "marked-${bill.key}", bill.title, "Was due ${dayMonth.format(bill.dueDate)} · marked ${dayMonth.format(bill.markedOn)}", bill.amount,
                    restore = {
                        onIntent(Intent.RestoreAttention(bill.key))
                        bill.attentionKey?.let { onIntent(Intent.RestoreAttention(it)) }
                        paidRepository.remove(bill.key)
                    }
                )
            }
    }
    var paidOpen by rememberSaveable { mutableStateOf(true) }

    CompositionLocalProvider(LocalContentInset provides Spacing.lg) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = billsList,
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            var slot = 0
            if (summary.count > 0) {
                val at = slot++
                item(key = "bills-hero") { BillsHero(summary, gate.rememberIsEntering(at), itemMotion().enterOnce(gate, at)) }
            } else {
                val at = slot++
                item(key = "no-bills") {
                    EmptyState(
                        "No bills due — you're clear",
                        "Nothing is due in the next 30 days. Card bills and auto-debits show up here when their messages arrive.",
                        itemMotion().enterOnce(gate, at)
                    )
                }
            }
            // Your own reminders belong with what is coming due, so the page is not bare when no bill is.
            if (state.planReminders.isNotEmpty()) {
                val at = slot++
                item(key = "reminders") {
                    BillsSection("Your reminders", itemMotion().enterOnce(gate, at)) {
                        state.planReminders.take(REMINDERS_SHOWN).forEachIndexed { index, due ->
                            if (index > 0) RowDivider()
                            val reminder = due.reminder
                            val key = PlanKeys.reminder(reminder)
                            val done = PlanExtraAction("Done", "Mark ${reminder.title} as done") {
                                if (reminder.repeatsMonthly) {
                                    // This month's is done: it comes back the day after, for next month.
                                    onIntent(Intent.PlanSnooze(key, reminder.title, PlanKind.REMINDER, due.on.plusDays(1)))
                                    planHandler.announce("Done: ${reminder.title}") { onIntent(Intent.PlanRestore(key)) }
                                } else {
                                    onIntent(Intent.RemoveReminder(reminder.id))
                                    planHandler.announce("Done: ${reminder.title}") { onIntent(Intent.SaveReminder(reminder)) }
                                }
                            }
                            PlanItemFrame(reminder.title, key, PlanKind.REMINDER, today, planHandler, extras = listOf(done), onOpen = { onIntent(Intent.OpenReminders) }) { more ->
                                val days = BillsMath.daysUntil(due.on, today)
                                PlanRow(
                                    title = reminder.title,
                                    modifier = Modifier.rowTap("Open reminders") { onIntent(Intent.OpenReminders) },
                                    leading = { DateBadge(due.on, if (days <= 0) Danger else if (days <= 2) Warn else MaterialTheme.colorScheme.secondary) },
                                    caption = {
                                        Caption(
                                            BillsMath.dueCaption(due.on, today).replaceFirst("Due ", "").replaceFirstChar { it.uppercase() } +
                                                if (reminder.repeatsMonthly) " · every month" else "",
                                            color = if (days < 0) Danger else mutedColor(),
                                            maxLines = 2
                                        )
                                    },
                                    amount = reminder.amount?.let { amt -> { RowAmount(DisplayFormatters.rupees(amt), MaterialTheme.colorScheme.secondary) } },
                                    more = more
                                )
                            }
                        }
                        if (state.planReminders.size > REMINDERS_SHOWN) LinkButton("All reminders", { onIntent(Intent.OpenReminders) })
                    }
                }
            }
            if (open.isNotEmpty() || paidLines.isNotEmpty()) {
                val at = slot++
                item(key = "upcoming") {
                    Box(Modifier.tourAnchor(TourAnchor.BILLS_DUE)) {
                        BillsSection(
                            "Due", itemMotion().enterOnce(gate, at),
                            trailing = { Caption(if (open.isEmpty()) "All clear" else DisplayFormatters.count(open.size, "bill", "bills")) }
                        ) {
                            AnimatedColumnItems(open, itemKey = { it.markedPaidKey }) { bill, index ->
                                Column {
                                    if (index > 0) RowDivider()
                                    val billKey = bill.markedPaidKey
                                    // A row that has left the list is one that was just paid: it keeps reading "Paid" while it closes.
                                    val leaving = billKey in settling || billKey !in openKeys
                                    val row: @Composable (@Composable () -> Unit) -> Unit = { more ->
                                        UpcomingRow(
                                            bill, today, onOpen = { onIntent(Intent.OpenMessage(bill.message.id)) }, onMarkPaid = onMarkPaid,
                                            more = more, settling = leaving
                                        ) { onIntent(Intent.OpenLink(it)) }
                                    }
                                    // Every bill stays on this tab, even an overdue one that was snoozed or dismissed (dismissed means "not in my plans", not "paid"); a snooze says so.
                                    val key = PlanKeys.bill(bill)
                                    PlanItemFrame(bill.title, key, PlanKind.BILL, today, planHandler, hide = PlanActions.hideFor(key, state.planActions, today)?.takeIf { it.isSnooze }, content = row)
                                }
                            }
                            if (open.isEmpty()) Caption("Nothing left to pay. Well done.", Modifier.padding(vertical = Spacing.sm), maxLines = 2)
                            AppearVisibility(paidLines.isNotEmpty()) {
                                Column(Modifier.padding(top = Spacing.sm)) {
                                    RowDivider()
                                    CollapsibleHeader(
                                        "Paid", paidLines.size, paidOpen,
                                        onToggle = { haptics.tick(); paidOpen = !paidOpen },
                                        modifier = Modifier.semantics { stateDescription = if (paidOpen) "Expanded" else "Collapsed" }
                                    )
                                    AppearVisibility(paidOpen) {
                                        AnimatedColumnItems(paidLines, itemKey = { it.key }) { line, index ->
                                            Column {
                                                if (index > 0) RowDivider()
                                                PaidBillRow(line.title, line.caption, DisplayFormatters.rupees(line.amount), line.restore)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.refunds.isNotEmpty()) {
                val at = slot++
                item(key = "refunds") {
                    BillsSection("Refunds", itemMotion().enterOnce(gate, at)) {
                        state.refunds.forEachIndexed { index, refund ->
                            if (index > 0) RowDivider()
                            RefundRow(refund) { onIntent(Intent.OpenMessage(refund.message.id)) }
                        }
                    }
                }
            }
            if (state.owed.isNotEmpty()) {
                val at = slot++
                item(key = "owed") {
                    BillsSection("Friends owe you", itemMotion().enterOnce(gate, at)) {
                        state.owed.forEachIndexed { index, share ->
                            if (index > 0) RowDivider()
                            OwedRow(share, onSettle = { onIntent(Intent.SettleShare(share.split.id, share.person.name)) }) {
                                onIntent(Intent.OpenMessage(share.split.messageId))
                            }
                        }
                    }
                }
            }
            item(key = "friends-link") { Box(Modifier.tourAnchor(TourAnchor.BILLS_FRIENDS)) { FriendsLink(state, onIntent) } }
            val recurring = recurringArranged
            if (recurring.isNotEmpty()) {
                val at = slot++
                item(key = "subscriptions") {
                    Box(Modifier.tourAnchor(TourAnchor.BILLS_REPEATS)) {
                        BillsSection("Repeats monthly", itemMotion().enterOnce(gate, at)) {
                            recurring.forEachIndexed { index, listItem ->
                                androidx.compose.runtime.key(listItem.listKey) {
                                    if (index > 0) RowDivider()
                                    val entry: @Composable (RecurringItem, Boolean) -> Unit = { item, grouped ->
                                        SubscriptionRow(
                                            item, today,
                                            onConfirm = { onIntent(Intent.ConfirmRecurring(item.subscription.key)) },
                                            onIgnore = { onIntent(Intent.IgnoreRecurring(item.subscription.key)) },
                                            menu = merge.menuFor(item.subscription, group.entryMenu(item.subscription, grouped))
                                        ) { onIntent(Intent.OpenMessage(item.subscription.messageId)) }
                                    }
                                    when (listItem) {
                                        is RecurringListItem.Single -> entry(listItem.item, false)
                                        is RecurringListItem.Group -> BillsGroupBlock(listItem, group) { member -> entry(member, true) }
                                    }
                                }
                            }
                            LinkButton("Merge duplicates, yearly cost, how to cancel", { onIntent(Intent.OpenSubscriptions) })
                        }
                    }
                }
            } else if (state.subscriptionOverview.lines.isNotEmpty()) {
                // Everything that repeats was cancelled or is paused: the full list is still one tap away.
                item(key = "subscriptions-link") { LinkButton("Subscriptions", { onIntent(Intent.OpenSubscriptions) }) }
            }
            item(key = "marked-paid") { PaidBillsLink(onIntent) }
        }
    }
}

@Composable
private fun RefundRow(item: RefundItem, onOpen: () -> Unit) {
    val dayMonth = remember { FormatCache.pattern("d MMM", Locale.getDefault()) }
    val arrived = item.arrivedOn
    val status = if (arrived == null) "On the way since " + dayMonth.format(item.startedOn) else "Arrived " + dayMonth.format(arrived)
    val merchant = item.message.merchant
    PlanRow(
        title = item.from,
        modifier = Modifier.rowTap("Open details", onOpen),
        leading = { MerchantChip(merchant.logo, imageKey = merchant.imageKey, name = merchant.name, description = item.from, size = RowTile) },
        caption = { Caption(status, color = if (item.isPending) mutedColor() else MaterialTheme.colorScheme.primary, maxLines = 2) },
        amount = { RowAmount(DisplayFormatters.rupees(item.amount), MaterialTheme.colorScheme.secondary) }
    )
}

@Composable
private fun OwedRow(share: SplitShare, onSettle: () -> Unit, onOpen: () -> Unit) {
    PlanRow(
        title = share.person.name,
        modifier = Modifier.rowTap("Open details", onOpen),
        leading = { MerchantChip(MerchantLogo.PERSON, name = share.person.name, description = share.person.name, size = RowTile) },
        caption = { Caption("For ${share.split.title}", maxLines = 2) },
        amount = { RowAmount(DisplayFormatters.rupees(share.person.share), MaterialTheme.colorScheme.secondary) },
        actions = listOf(RowAction("Mark as paid", ButtonStyle.OUTLINE, "Mark ${share.person.name}'s share as paid") { onSettle() })
    )
}

/** A group in the "Repeats monthly" card: the monthly sum on its header and, when opened, its entries as ordinary rows. */
@Composable
private fun BillsGroupBlock(
    item: RecurringListItem.Group<RecurringItem>,
    group: GroupActions,
    entry: @Composable (RecurringItem) -> Unit
) {
    // Closed to begin with; remembered per group, and across rotation. The chevron turns once as it opens.
    var expanded by rememberSaveable(item.group.id) { mutableStateOf(false) }
    val haptics = rememberHaptics()
    GroupHeaderRow(
        name = item.name, subtitle = item.countLabel() + " · " + item.nextDueLabel(), spokenAmounts = item.spokenTotals(),
        expanded = expanded, onToggle = { haptics.tick(); expanded = !expanded },
        picking = false, picked = false, onPick = { expanded = !expanded },
        menu = group.groupMenu(item.group, canCancel = true)
    ) {
        RowAmount(DisplayFormatters.rupees(item.monthly), MaterialTheme.colorScheme.secondary)
    }
    GroupMembers(expanded) {
        item.members.forEach { member -> androidx.compose.runtime.key(member.subscription.key) { entry(member) } }
    }
}

@Composable
private fun SubscriptionRow(
    recurring: RecurringItem,
    today: LocalDate,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    menu: List<MenuEntry>,
    onOpen: () -> Unit
) {
    val item: Subscription = recurring.subscription
    val name = item.fullName()
    val reference = item.reference
    PlanRow(
        title = name,
        modifier = Modifier.rowTap("Open details", onOpen),
        leading = { MerchantChip(MerchantLogo.PERSON, name = name, description = name, size = RowTile) },
        caption = {
            Caption(
                BillsMath.dueCaption(item.nextDue, today) + " · " + recurring.expectedLabel + " · paid ${item.times} months running",
                maxLines = 2
            )
            if (reference.isNotEmpty()) Caption(reference)
            Caption(if (recurring.isConfirmed) "Confirmed" else "Looks recurring — is it?")
        },
        amount = { RowAmount(DisplayFormatters.rupees(item.amount), MaterialTheme.colorScheme.secondary) },
        more = { RowMenu(name, menu) },
        actions = if (recurring.isConfirmed) emptyList() else listOf(
            RowAction("Confirm", ButtonStyle.OUTLINE, "Confirm $name repeats") { onConfirm() },
            RowAction("Not recurring", ButtonStyle.TEXT, "$name is not recurring") { onIgnore() }
        )
    )
}
