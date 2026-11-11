package com.zyke.message.presentation.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.zyke.message.R
import com.zyke.message.presentation.common.LocalOpenSettings
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.accounts.AccountInsights
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.networth.NetWorth
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/** A bank account on the Accounts tab: who, how much and how fresh, this cycle's flow, a trend, and what needs attention. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BankAccountTile(
    account: AccountSummary,
    series: List<BigDecimal>,
    today: LocalDate,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLeaveOut: () -> Unit,
    /** Opens "Check balance by SMS"; null hides the action. */
    onCheckBalance: (() -> Unit)? = null,
    /** Opens "Balance check settings" (number, words, ask before sending); null hides the menu item. */
    onBalanceSettings: (() -> Unit)? = null
) {
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val balance = account.balance
    val requestStatus = if (onCheckBalance != null) rememberRequestStatus(account.key, account.balanceAt) else null
    val (checkState, checkProgress) = rememberCheckState(account.key, account.balanceAt)
    val checkTap = rememberCheckTap(BalanceTarget(account.key, account.bankName, account.bankLabel, account.last4, isProvidentFund = false), onCheckBalance ?: {})
    val asOf = account.balanceAsOf
    val hidden = LocalPrivacyMode.current.hidden
    val low = AccountInsights.isLowBalance(balance)
    val stale = AccountInsights.isStale(asOf, today)
    // One short line: when the balance is from, and how many payments came after it. The full arithmetic is in the details.
    val freshness = when {
        asOf == null -> "No balance yet"
        account.balanceAdjustment != null && account.movementCount > 0 ->
            "As of ${AccountInsights.age(asOf, today)} \u00b7 ${DisplayFormatters.count(account.movementCount, "payment", "payments")} since"
        else -> "As of ${AccountInsights.age(asOf, today)}"
    }
    val description = buildString {
        append(account.title).append(", ").append(account.kind.label).append(" ending ").append(account.last4).append(". ")
        if (hidden) append("Balance hidden. ") else append(balance?.let { "Balance ${DisplayFormatters.rupees(it)}, " } ?: "No balance, ").append(freshness).append(". ")
        if (!hidden) append("This cycle in ${DisplayFormatters.rupees(account.received)}, out ${DisplayFormatters.rupees(account.spent)}. ")
        if (!hidden) balanceWhy(account)?.let { append(it).append(". ") }
        if (low) append("Low balance. ")
        if (stale) append("Balance may be out of date. ")
        if (requestStatus != null) append(requestStatus)
    }
    FintechCard(Modifier.semantics { contentDescription = description }, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountChip(account, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(account.title)
                Caption("${account.kind.label} •••• ${account.last4}")
            }
            AccountActions(account.title, isCard = false, onOpen, onRename, onLeaveOut, onCheckBalance, onBalanceSettings)
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                PrivateAmount(
                    balance?.let { DisplayFormatters.rupees(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    fit = true
                )
                Caption(freshness, maxLines = 1)
                BalanceWhy(account, onRename)
            }
            if (series.size >= 2) {
                Spacer(Modifier.width(12.dp))
                Sparkline(series, Modifier.width(88.dp).height(32.dp))
            }
        }
        FlowAndChips(account, low, stale)
        if (onCheckBalance != null) CheckBalanceRow("Check balance", checkState, checkProgress, checkTap, Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowAndChips(account: AccountSummary, low: Boolean, stale: Boolean) {
    val inShare = remember(account.spent, account.received) { AccountInsights.inShare(account.spent, account.received) }
    if (inShare != null) {
        Column(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                val share = inShare.coerceIn(0.03f, 0.97f)
                Box(Modifier.weight(share).fillMaxHeight().background(Good))
                Box(Modifier.weight(1f - share).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)))
            }
            Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("In ${DisplayFormatters.rupees(account.received)}")
                Caption("Out ${DisplayFormatters.rupees(account.spent)}")
            }
        }
    }
    if (low || stale) {
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (low) StatusPill("Low balance", Tone.BAD)
            if (stale) StatusPill("Balance may be old", Tone.WARN)
        }
    }
}

private val WHY_DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * The arithmetic under a bank balance: where it started and what has moved since, so a total that looks wrong can be
 * checked. Null when there is no balance to explain or nothing has moved. Amounts in it are hidden with the others.
 */
internal fun balanceWhy(account: AccountSummary): String? {
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val asOf = account.balanceAsOf ?: return null
    if (account.balance == null || account.isCard) return null
    val adjustment = account.balanceAdjustment
    val day = WHY_DAY.format(asOf)
    val source = if (account.balanceByYou) "Balance set by you on $day" else "From the $day message"
    val parts = ArrayList<String>()
    when {
        adjustment != null -> {
            val sign = if (adjustment.signum() < 0) "\u2212" else "+"
            parts += "$sign${DisplayFormatters.rupees(adjustment.abs())} across ${DisplayFormatters.count(account.movementCount, "payment", "payments")} since"
        }
        account.adjustmentRefused ->
            parts += "${DisplayFormatters.count(account.movementCount, "payment", "payments")} since would take it below zero, so ${if (account.movementCount == 1) "it is" else "they are"} not applied"
    }
    if (account.paymentsNotCounted > 0) parts += "${DisplayFormatters.count(account.paymentsNotCounted, "payment", "payments")} not counted"
    return if (parts.isEmpty()) null else "$source, ${parts.joinToString(", ")}"
}

/** Only when something needs doing: a short line and the button; the full arithmetic lives in the account's details. */
@Composable
private fun BalanceWhy(account: AccountSummary, onSetBalance: () -> Unit) {
    val noBalance = account.balance == null
    val unsure = account.adjustmentRefused || account.paymentsNotCounted > 0
    if (!noBalance && !unsure) return
    val line = when {
        noBalance && account.paymentCount > 0 -> "${DisplayFormatters.count(account.paymentCount, "payment", "payments")} not added up"
        noBalance -> null
        account.adjustmentRefused -> "Payments since could not be applied"
        else -> "${DisplayFormatters.count(account.paymentsNotCounted, "payment", "payments")} not counted"
    }
    if (line != null) Caption(line, maxLines = 1)
    AppButton(if (noBalance) "Set balance" else "Fix balance", onSetBalance, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
}

/** A credit card's line under the deck: what is due or unbilled, how much of the limit is in use, and the actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardStatusPanel(
    card: AccountSummary,
    today: LocalDate,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLeaveOut: () -> Unit,
    /** The upcoming-bill entry of this card's open bill, when there is one: it carries what "Mark as paid" needs. */
    bill: com.zyke.message.domain.home.UpcomingItem? = null,
    /** The bill was marked as paid by hand (it is open in the messages but no longer upcoming). */
    markedPaid: Boolean = false,
    onMarkPaid: () -> Unit = {},
    onPay: (String) -> Unit = {}
) {
    val due = card.dueDate
    val dueAmount = card.dueAmount
    val unbilled = card.unbilled?.takeIf { it.signum() > 0 }
    val partPaid = card.billPartPaid?.takeIf { dueAmount != null && !card.billPaid }
    val partLeft = if (partPaid != null && dueAmount != null) (dueAmount - partPaid).max(BigDecimal.ZERO) else null
    val limit = card.userCard?.creditLimit
    val owed = remember(card) { NetWorth.cardOwed(card) }
    val use = remember(owed, limit) { AccountInsights.utilisation(owed, limit) }
    val hidden = LocalPrivacyMode.current.hidden
    val dueOpen = due != null && !due.isBefore(today) && dueAmount != null && dueAmount.signum() > 0 && !card.billPaid
    val description = buildString {
        append(card.title).append(" ending ").append(card.last4).append(". ")
        if (!hidden) {
            if (dueOpen && dueAmount != null) append("Bill ${DisplayFormatters.rupees(dueAmount)}, ${DisplayFormatters.dueLabel(due!!, today)}. ")
            if (partPaid != null && partLeft != null) append("Part paid ${DisplayFormatters.rupees(partPaid)}, ${DisplayFormatters.rupees(partLeft)} left. ")
            if (unbilled != null) append("Unbilled ${DisplayFormatters.rupees(unbilled)}. ")
            if (use != null) append("${(use * 100).toInt()} percent of the limit used. ")
        } else append("Amounts hidden.")
    }
    FintechCard(Modifier.semantics { contentDescription = description }, onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
        FlowRow(Modifier.weight(1f).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dueOpen && due != null && !markedPaid) {
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
                StatusPill(DisplayFormatters.dueLabel(due, today), if (days <= 3) Tone.BAD else Tone.WARN)
            }
            if (card.billPaid) StatusPill("Bill paid", Tone.GOOD)
            else if (partPaid != null) StatusPill("Part paid", Tone.WARN)
            if (markedPaid && dueOpen) StatusPill("Marked as paid", Tone.GOOD)
            if (unbilled != null) StatusPill("Unbilled", Tone.NEUTRAL)
        }
        AccountActions(card.title, isCard = true, onOpen, onRename, onLeaveOut)
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Caption("BILL")
                PrivateAmount(dueAmount?.let { DisplayFormatters.rupees(it) } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                Caption("UNBILLED")
                PrivateAmount(unbilled?.let { DisplayFormatters.rupees(it) } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true)
            }
        }
        if (partPaid != null && partLeft != null) {
            Caption(
                "${DisplayFormatters.rupees(partPaid)} of ${DisplayFormatters.rupees(dueAmount ?: partPaid)} paid \u00B7 ${DisplayFormatters.rupees(partLeft)} left",
                Modifier.padding(top = 8.dp), color = ChartColors.warning, maxLines = 2
            )
        }
        // Paying it is one tap away, and so is saying it was paid some other way.
        val payLink = bill?.paymentLink ?: card.paymentLink
        if (dueOpen && bill != null && bill.paidOn == null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (payLink != null) {
                    AppButton(
                        "Pay bill", { onPay(payLink) },
                        Modifier.weight(1f).semantics { contentDescription = "Pay the ${card.title} bill" },
                        style = ButtonStyle.PRIMARY, size = ButtonSize.COMPACT
                    )
                }
                AppButton(
                    "Mark as paid", onMarkPaid,
                    Modifier.weight(1f).semantics { contentDescription = "Mark the ${card.title} bill as paid" },
                    style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
                )
            }
        }
        if (use != null && limit != null) {
            com.zyke.message.presentation.dashboard.components.ShareBar(
                use,
                color = when {
                    use >= 0.9f -> MaterialTheme.colorScheme.error
                    use >= 0.6f -> ChartColors.warning
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Caption(
                if (hidden) "${(use * 100).toInt()}% of limit used"
                else "${(use * 100).toInt()}% of ${DisplayFormatters.rupees(BigDecimal(limit))} limit used",
                Modifier.padding(top = 4.dp), maxLines = 2
            )
        } else {
            Caption("Add this card's limit to see how much of it is used", Modifier.padding(top = 8.dp), maxLines = 2)
        }
    }
}

/**
 * The one overflow button at the end of a tile's top line (48dp). Details is also the tile's own tap, but it stays in the
 * menu so a screen-reader user finds all three in one place; a card adds "Edit card", which opens the cards form in Settings.
 */
@Composable
private fun AccountActions(
    name: String,
    isCard: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLeaveOut: () -> Unit,
    onCheckBalance: (() -> Unit)? = null,
    onBalanceSettings: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val openSettings = LocalOpenSettings.current
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.baseline_more_horiz_24), contentDescription = "Actions for $name")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionItem("Details", null, R.drawable.ic_info_24) { open = false; onOpen() }
            if (onCheckBalance != null) ActionItem("Check balance by SMS", "Text your bank for the latest balance", R.drawable.baseline_message_24) { open = false; onCheckBalance() }
            if (onBalanceSettings != null) ActionItem("Balance check settings", "Number, words, ask before sending", R.drawable.ic_info_24) { open = false; onBalanceSettings() }
            ActionItem("Edit", "Name, bank and type", R.drawable.ic_edit_24) { open = false; onRename() }
            if (isCard) ActionItem("Edit card", "Limit, bill dates and look", R.drawable.ic_card_24) { open = false; openSettings() }
            ActionItem("Leave out of totals", "Hidden from totals and lists", R.drawable.ic_visibility_off_24) { open = false; onLeaveOut() }
        }
    }
}

@Composable
private fun ActionItem(label: String, hint: String?, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

/** A tiny line of the balance's recent readings; decorative, so it carries no description of its own. */
@Composable
internal fun Sparkline(values: List<BigDecimal>, modifier: Modifier = Modifier) {
    val points = remember(values) { values.map { it.toDouble() } }
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val min = points.min()
        val range = (points.max() - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / range).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val last = points.last()
        drawCircle(color, 3.dp.toPx(), Offset(size.width, size.height - ((last - min) / range).toFloat() * size.height))
    }
}
