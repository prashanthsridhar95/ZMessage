package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.cards.CardLimits
import com.zyke.message.domain.cards.CardSpend
import com.zyke.message.domain.cards.CardSpendView
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.presentation.common.ActionCircleRow
import com.zyke.message.presentation.common.ActionSpec
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LimitTone
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.SpendCard
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** What the Cards tab needs to show one card: its numbers, its cycle, and what its buttons do. */
internal class CardTabModel(
    val card: AccountSummary,
    val spend: CardSpendView,
    val today: LocalDate,
    val limit: Long?,
    /** The card's open bill in the upcoming list (what "Mark paid" stores), when there is one. */
    val bill: com.zyke.message.domain.home.UpcomingItem?,
    val markedPaid: Boolean
) {
    val dueOpen: Boolean get() {
        val due = card.dueDate
        val amount = card.dueAmount
        return due != null && !due.isBefore(today) && amount != null && amount.signum() > 0 && !card.billPaid
    }
    val payLink: String? get() = bill?.paymentLink ?: card.paymentLink
    val canMarkPaid: Boolean get() = dueOpen && bill != null && bill.paidOn == null
    val tone: LimitTone get() = when (CardLimits.status(spend.spent, limit)) {
        CardLimits.Status.NONE -> LimitTone.NONE
        CardLimits.Status.OK -> LimitTone.OK
        CardLimits.Status.NEAR -> LimitTone.NEAR
        CardLimits.Status.OVER -> LimitTone.OVER
    }
}

@Composable
internal fun rememberCardTabModel(
    card: AccountSummary,
    messages: List<com.zyke.message.domain.model.ClassifiedMessage>,
    today: LocalDate,
    zone: ZoneId,
    bill: com.zyke.message.domain.home.UpcomingItem?,
    markedPaid: Boolean
): CardTabModel {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val limits by container.cardLimits.limits.collectAsStateWithLifecycle()
    val statementDay = card.userCard?.statementDay ?: card.suggestedStatementDay
    val spend = remember(messages, card.last4, statementDay, today) { CardSpend.view(messages, card.last4, statementDay, today, zone) }
    return CardTabModel(card, spend, today, limits[card.key], bill, markedPaid)
}

/** "Credit card · •••• 5580": what the card is, under its name. */
internal fun cardKindLine(card: AccountSummary): String =
    "${if (card.kind == com.zyke.message.domain.accounts.AccountKind.DEBIT_CARD) "Debit card" else "Credit card"} · •••• ${card.last4}"

/** The line under the amount in the spend card: what is left of the limit and when it resets; null says nothing at all. */
internal fun limitNote(model: CardTabModel): String? {
    val limit = model.limit ?: return null
    val left = CardLimits.left(model.spend.spent, limit) ?: return null
    val resets = CardLimits.resetsInDays(model.today, model.spend.cycleEnd, model.spend.cycleKnown)
    val tail = resets?.let { " · resets in ${DisplayFormatters.count(it, "day", "days")}" }.orEmpty()
    return if (left.signum() < 0) "${DisplayFormatters.rupees(left.negate())} over the limit$tail" else "${DisplayFormatters.rupees(left)} left$tail"
}

/** The body of the Cards tab under the header region: actions, the spend card, the bill, recent spends. */
internal fun LazyListScope.cardBodyItems(
    model: CardTabModel,
    onPay: (String) -> Unit,
    onMarkPaid: () -> Unit,
    onOpen: () -> Unit,
    onEditCards: () -> Unit
) {
    val card = model.card
    item(key = "card-actions-" + card.key) {
        var details by rememberSaveable { mutableStateOf(false) }
        var editing by rememberSaveable { mutableStateOf(false) }
        val payLink = model.payLink
        ActionCircleRow(
            listOf(
                ActionSpec("Pay bill", R.drawable.ic_card_24, { payLink?.let(onPay) }, primary = true, enabled = model.dueOpen && payLink != null,
                    description = "Pay the ${card.title} bill"),
                ActionSpec("Details", R.drawable.ic_info_24, { details = true }),
                ActionSpec("Limit", R.drawable.ic_tune_24, { editing = true }, description = "Set a monthly limit for ${card.title}"),
                ActionSpec("Mark paid", R.drawable.baseline_check_24, onMarkPaid, enabled = model.canMarkPaid, description = "Mark the ${card.title} bill as paid")
            )
        )
        if (details) CardDetailsSheet(model, onEditCards) { details = false }
        if (editing) CardLimitSheet(model) { editing = false }
    }
    item(key = "card-spend-" + card.key) {
        var editing by rememberSaveable { mutableStateOf(false) }
        val limit = model.limit
        SpendCard(
            title = if (model.spend.cycleKnown) "Spent this cycle" else "Spent this month",
            amount = DisplayFormatters.rupees(model.spend.spent),
            limit = limit?.let { DisplayFormatters.rupees(BigDecimal(it)) },
            onSetLimit = { editing = true },
            onEditLimit = { editing = true },
            progress = if (limit != null) CardLimits.progress(model.spend.spent, limit) else null,
            tone = model.tone,
            note = limitNote(model),
            shares = model.spend.shares,
            emptyText = "Nothing spent on this card in this cycle yet."
        )
        if (editing) CardLimitSheet(model) { editing = false }
    }
    if (model.dueOpen || card.billPaid || card.unbilled?.signum() == 1) item(key = "card-bill-" + card.key) { BillPanel(model) }
    item(key = "card-recent-" + card.key) { RecentList("Recent spends", model.spend.recent, "No spends on this card yet.", onOpen) }
    item(key = "add-card") {
        AppButton("Add or edit cards", onEditCards, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
    }
}

/** The bill: when it is due, what it is, the minimum, and whether it is paid: one calm panel. */
@Composable
private fun BillPanel(model: CardTabModel) {
    val card = model.card
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    val due = card.dueDate
    val amount = card.dueAmount
    val partPaid = card.billPartPaid?.takeIf { amount != null && !card.billPaid }
    FintechCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bill", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when {
                card.billPaid -> StatusPill("Paid", Tone.GOOD)
                model.markedPaid && model.dueOpen -> StatusPill("Marked as paid", Tone.GOOD)
                partPaid != null -> StatusPill("Part paid", Tone.WARN)
                model.dueOpen && due != null -> StatusPill(DisplayFormatters.dueLabel(due, model.today), if (java.time.temporal.ChronoUnit.DAYS.between(model.today, due) <= 3) Tone.BAD else Tone.WARN)
            }
        }
        if (amount != null) BillLine("Statement", DisplayFormatters.rupees(amount))
        card.minimumDue?.takeIf { it.signum() > 0 && !card.billPaid }?.let { BillLine("Minimum due", DisplayFormatters.rupees(it)) }
        if (partPaid != null && amount != null) BillLine("Paid so far", DisplayFormatters.rupees(partPaid) + " of " + DisplayFormatters.rupees(amount))
        if (due != null) BillLine("Due date", dayFormat.format(due))
        card.unbilled?.takeIf { it.signum() > 0 }?.let { BillLine("Unbilled", DisplayFormatters.rupees(it)) }
    }
}

@Composable
private fun BillLine(label: String, value: String) {
    // Side by side; one above the other when text is large, so neither is squeezed.
    val stacked = com.zyke.message.presentation.common.LocalResponsive.current.fontScale >= 1.5f
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    if (stacked) {
        Column(Modifier.padding(top = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrivateAmount(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrivateAmount(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The last five rows of an account or card, newest first; "See all" opens its own page. Money in is marked with a plus. */
@Composable
internal fun RecentList(title: String, rows: List<com.zyke.message.domain.model.ClassifiedMessage>, emptyText: String, onOpen: () -> Unit) {
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AppButton("See all", onOpen, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
        if (rows.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val income = Counting.income(m)
            val amount = income ?: Counting.spend(m) ?: BigDecimal.ZERO
            val date = dayFormat.format(java.time.Instant.ofEpochMilli(m.message.date).atZone(zone).toLocalDate())
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(m.merchant.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${m.category.displayString} · $date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                PrivateAmount((if (income != null) "+" else "") + DisplayFormatters.rupees(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/**
 * "Details": only what Zyke knows from messages and the person's own settings. It never has the full number, the expiry or the
 * CVV, so none is shown or made up; the sheet says so.
 */
@Composable
private fun CardDetailsSheet(model: CardTabModel, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val card = model.card
    val user = card.userCard
    val dayFormat = FormatCache.pattern("d MMM yyyy", Locale.getDefault())
    AdaptiveSheet(title = "Card details", subtitle = card.title, onDismiss = onDismiss, contentSpacing = 4.dp) {
        FintechCard {
            DetailLine("Bank", card.bankLabel.ifBlank { "Not known" })
            DetailLine("Card ending", card.last4)
            user?.network?.let { DetailLine("Network", it.label) }
            val statementDay = user?.statementDay ?: card.suggestedStatementDay
            DetailLine("Last statement", card.statementOn?.let { dayFormat.format(it) } ?: statementDay?.let { "Day $it of the month" } ?: "Not known")
            DetailLine("Due date", card.dueDate?.let { dayFormat.format(it) } ?: "Not known")
            DetailLine("Minimum due", card.minimumDue?.let { DisplayFormatters.rupees(it) } ?: "Not known")
            DetailLine("Credit limit", user?.creditLimit?.let { DisplayFormatters.rupees(BigDecimal(it)) } ?: "Not set")
        }
        Text(
            "This is what your messages and settings say. Zyke never has your full card number, expiry or CVV, so it cannot show them.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppButton("Edit card", { onDismiss(); onEdit() }, Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.TONAL)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val stacked = com.zyke.message.presentation.common.LocalResponsive.current.fontScale >= 1.5f
    if (stacked) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrivateAmount(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrivateAmount(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        }
    }
}

/** "Monthly limit": the big amount, a slider, preset chips and a full-width Save. The limit is the person's own; nothing is sent anywhere. */
@Composable
internal fun CardLimitSheet(model: CardTabModel, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val card = model.card
    var value by rememberSaveable { mutableLongStateOf(CardLimits.snap(model.limit ?: card.userCard?.creditLimit ?: 1_00_000L)) }
    val presets = remember(card.userCard?.creditLimit) { CardLimits.presets(card.userCard?.creditLimit) }
    AdaptiveSheet(
        title = "Monthly limit", subtitle = "${card.title} · •••• ${card.last4}", onDismiss = onDismiss, contentSpacing = 16.dp,
        footer = {
            AppButton("Save limit", { container.cardLimits.set(card.key, value); onDismiss() }, Modifier.fillMaxWidth())
            if (model.limit != null) AppButton("Remove limit", { container.cardLimits.clear(card.key); onDismiss() }, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT)
        }
    ) {
        PrivateAmount(
            DisplayFormatters.rupees(BigDecimal(value)), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, fit = true
        )
        Slider(
            value = CardLimits.fraction(value),
            onValueChange = { value = CardLimits.fromFraction(it) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Monthly limit"
                stateDescription = DisplayFormatters.rupees(BigDecimal(value))
            }
        )
        Chips(presets.map { ChipOption(DisplayFormatters.rupees(BigDecimal(it)), it == value) }, { value = presets[it] })
        Text(
            "Zyke shows how much of this is left each cycle. It does not block payments or send anything.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
