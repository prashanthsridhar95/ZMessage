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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/** A bank account on the Accounts tab: who, how much and how fresh, this cycle's flow, a trend, and what needs attention. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BankAccountTile(
    account: AccountSummary,
    series: List<BigDecimal>,
    today: LocalDate,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLeaveOut: () -> Unit
) {
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val balance = account.balance
    val asOf = account.balanceAsOf
    val hidden = LocalPrivacyMode.current.hidden
    val low = AccountInsights.isLowBalance(balance)
    val stale = AccountInsights.isStale(asOf, today)
    val freshness = when {
        asOf == null -> "balance not stated"
        account.balanceAdjustment != null -> "stated ${AccountInsights.age(asOf, today)}, plus later payments"
        else -> "as of ${AccountInsights.age(asOf, today)}"
    }
    val description = buildString {
        append(account.title).append(", ").append(account.kind.label).append(" ending ").append(account.last4).append(". ")
        if (hidden) append("Balance hidden. ") else append(balance?.let { "Balance ${DisplayFormatters.rupees(it)}, " } ?: "No balance, ").append(freshness).append(". ")
        if (!hidden) append("This cycle in ${DisplayFormatters.rupees(account.received)}, out ${DisplayFormatters.rupees(account.spent)}. ")
        if (low) append("Low balance. ")
        if (stale) append("Balance may be out of date.")
    }
    FintechCard(Modifier.semantics { contentDescription = description }, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountChip(account, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(account.title)
                Caption("${account.kind.label} •••• ${account.last4}")
            }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                PrivateAmount(
                    balance?.let { DisplayFormatters.rupees(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1
                )
                Caption(freshness, maxLines = 2)
            }
            if (series.size >= 2) {
                Spacer(Modifier.width(12.dp))
                Sparkline(series, Modifier.width(88.dp).height(32.dp))
            }
        }
        FlowAndChips(account, low, stale)
        ActionRow(onOpen, onRename, onLeaveOut)
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

/** A credit card's line under the deck: what is due or unbilled, how much of the limit is in use, and the actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardStatusPanel(
    card: AccountSummary,
    today: LocalDate,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLeaveOut: () -> Unit
) {
    val due = card.dueDate
    val dueAmount = card.dueAmount
    val unbilled = card.unbilled?.takeIf { it.signum() > 0 }
    val limit = card.userCard?.creditLimit
    val owed = remember(card) { NetWorth.cardOwed(card) }
    val use = remember(owed, limit) { AccountInsights.utilisation(owed, limit) }
    val hidden = LocalPrivacyMode.current.hidden
    val dueOpen = due != null && !due.isBefore(today) && dueAmount != null && dueAmount.signum() > 0 && !card.billPaid
    val description = buildString {
        append(card.title).append(" ending ").append(card.last4).append(". ")
        if (!hidden) {
            if (dueOpen && dueAmount != null) append("Bill ${DisplayFormatters.rupees(dueAmount)}, ${DisplayFormatters.dueLabel(due!!, today)}. ")
            if (unbilled != null) append("Unbilled ${DisplayFormatters.rupees(unbilled)}. ")
            if (use != null) append("${(use * 100).toInt()} percent of the limit used. ")
        } else append("Amounts hidden.")
    }
    FintechCard(Modifier.semantics { contentDescription = description }, onClick = onOpen) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dueOpen && due != null) {
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
                StatusPill(DisplayFormatters.dueLabel(due, today), if (days <= 3) Tone.BAD else Tone.WARN)
            }
            if (card.billPaid) StatusPill("Bill paid", Tone.GOOD)
            if (unbilled != null) StatusPill("Unbilled", Tone.NEUTRAL)
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Caption("BILL")
                PrivateAmount(dueAmount?.let { DisplayFormatters.rupees(it) } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Caption("UNBILLED")
                PrivateAmount(unbilled?.let { DisplayFormatters.rupees(it) } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
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
        ActionRow(onOpen, onRename, onLeaveOut)
    }
}

/** Open, rename and leave out, as real buttons rather than a menu to discover. */
@Composable
private fun ActionRow(onOpen: () -> Unit, onRename: () -> Unit, onLeaveOut: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AppButton("Details", onOpen, style = ButtonStyle.TEXT)
        AppButton("Rename", onRename, style = ButtonStyle.TEXT)
        AppButton("Leave out", onLeaveOut, style = ButtonStyle.TEXT)
    }
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
