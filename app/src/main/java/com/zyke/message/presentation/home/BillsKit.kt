package com.zyke.message.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.bills.BillsMath
import com.zyke.message.domain.bills.BillsSummary
import com.zyke.message.domain.bills.DueTone
import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowAmount
import com.zyke.message.presentation.messages.components.RowTile
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.CrossfadeKeyed
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.ProgressReveal
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole
import java.time.LocalDate

/*
 * The pieces the Bills tab and the pages beside it (reminders, goals, the calendar) share, so they read as one family:
 * one tap behaviour for a row, one card with a header, one hero, one status chip, one chevron, one confirm. docs/DESIGN.md
 * (Bills and Calendar) says where each is used.
 */

/** A row that dips while held and keeps its ripple, role and click label. */
@Composable
internal fun Modifier.rowTap(onClickLabel: String, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressFeedback(source)
        .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
}

/** A titled card: the shared header, then its rows. */
@Composable
internal fun BillsSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    FintechCard(modifier) {
        CardHeader(title, Modifier.padding(bottom = Spacing.sm), trailing = trailing)
        content()
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

/**
 * "Delete this?" before anything that cannot be taken back with one tap. The confirming tap gives the firmer buzz
 * ([com.zyke.message.presentation.motion.Haptics.reject]).
 */
@Composable
internal fun ConfirmDelete(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { AppButton(confirmLabel, { haptics.reject(); onConfirm() }, style = ButtonStyle.TEXT) },
        dismissButton = { AppButton("Keep it", onDismiss, style = ButtonStyle.TEXT) }
    )
}

/** The chevron at the end of something that opens and closes: it turns half a circle, once, when the state changes. */
@Composable
internal fun ChevronTurn(expanded: Boolean, modifier: Modifier = Modifier) {
    val motion = rememberMotion()
    val turn = animateFloatAsState(if (expanded) 180f else 0f, motion.tween(Motion.QUICK_MS), label = "chevron")
    Box(modifier.size(Spacing.xxxl), contentAlignment = Alignment.Center) {
        Text(
            "▾",
            Modifier.clearAndSetSemantics { }.graphicsLayer { rotationZ = turn.value },
            style = MaterialTheme.typography.labelLarge,
            color = mutedColor()
        )
    }
}

/** A header that folds the rows under it: the title and a count, the chevron turning. The state is the caller's to remember. */
@Composable
internal fun CollapsibleHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).rowTap(if (expanded) "Collapse $title" else "Expand $title", onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Caption(count.toString())
        ChevronTurn(expanded)
    }
}

/** What a bill row says about itself besides the date: nothing, Overdue, Part paid, or Paid. */
internal enum class BillStatus { NONE, OVERDUE, PART, PAID }

/** The status chip. A change between two statuses crossfades; the Paid chip's check mark scales in once. */
@Composable
internal fun BillStatusSlot(status: BillStatus, modifier: Modifier = Modifier) {
    CrossfadeKeyed(targetState = status, contentKey = { it }, modifier = modifier, label = "bill-status") { shown ->
        when (shown) {
            BillStatus.NONE -> Spacer(Modifier.size(0.dp))
            BillStatus.OVERDUE -> StatusPill("Overdue", Tone.BAD)
            BillStatus.PART -> StatusPill("Part paid", Tone.WARN)
            BillStatus.PAID -> PaidChip()
        }
    }
}

/** "Paid" with a check mark that fades and grows in once (nothing when motion is reduced). */
@Composable
internal fun PaidChip(modifier: Modifier = Modifier) {
    val motion = rememberMotion()
    var shown by remember { mutableStateOf(motion.reduced) }
    LaunchedEffect(Unit) { shown = true }
    val good = Good
    Row(
        modifier.background(good.copy(alpha = 0.14f), AppShapes.chip).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = if (motion.reduced) EnterTransition.None else
                fadeIn(motion.tween(Motion.STANDARD_MS)) + scaleIn(motion.tween(Motion.STANDARD_MS, Motion.Emphasized), initialScale = 0.4f),
            exit = ExitTransition.None
        ) {
            Text("✓", Modifier.padding(end = Spacing.xs).clearAndSetSemantics { }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = good)
        }
        Text("Paid", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = good, maxLines = 1)
    }
}

/**
 * This month's bills at a glance, the one hero of the Bills tab: what is still to pay (counting up on the first showing),
 * a bar for how much of the month's money is paid, and the plain words for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BillsHero(summary: BillsSummary, entering: Boolean, modifier: Modifier = Modifier) {
    FintechCard(modifier, level = Tonal.Hero) {
        CardHeader("Due this month")
        AnimatedAmount(
            summary.dueTotal, style = TypeRole.hero, color = MaterialTheme.colorScheme.primary, fit = true, fromZero = entering
        )
        Caption(
            when {
                summary.count == 0 -> "Nothing due this month"
                summary.dueCount == 0 -> "All paid. Nothing left to pay"
                else -> "still to pay across " + DisplayFormatters.count(summary.dueCount, "bill", "bills")
            },
            Modifier.padding(top = Spacing.xs), maxLines = 2
        )
        ProgressReveal(summary.fraction, Modifier.padding(top = Spacing.lg).clearAndSetSemantics { }, height = Spacing.sm)
        // Paid so far on the left, how many on the right; stacked on a narrow phone or at large text. A plain Row (not a wrapping
        // one) so the label is never squeezed by the amount beside it.
        val paidSoFar: @Composable () -> Unit = {
            Caption("Paid so far", maxLines = 1)
            Spacer(Modifier.width(Spacing.sm))
            AnimatedAmount(summary.paidTotal, style = MaterialTheme.typography.titleSmall, color = Good, fontWeight = FontWeight.SemiBold)
        }
        if (com.zyke.message.presentation.common.LocalResponsive.current.stackRows) {
            Column(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) { paidSoFar() }
                Caption(BillsMath.progressCaption(summary), maxLines = 2)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                paidSoFar()
                Spacer(Modifier.width(Spacing.md))
                Caption(BillsMath.progressCaption(summary), Modifier.weight(1f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/**
 * A bill on the Bills tab (and the next bill on Home): a logo tile, the name, when it is due in words that carry their own
 * colour, the amount, a status chip, and the buttons on their own line with equal widths. A part-paid bill draws how far it
 * has come. While [settling] (just marked paid, about to move to the Paid group) it already reads as paid.
 */
@Composable
internal fun UpcomingRow(
    item: UpcomingItem,
    today: LocalDate,
    onOpen: () -> Unit,
    onMarkPaid: (UpcomingItem) -> Unit,
    more: (@Composable () -> Unit)? = null,
    settling: Boolean = false,
    onPay: (String) -> Unit
) {
    val paidOn = item.paidOn
    val paid = paidOn != null || settling
    val tone = BillsMath.tone(item.date, today)
    val partPaid = item.partPaid
    val isPart = !paid && partPaid != null && partPaid.signum() > 0
    val status = when {
        paid -> BillStatus.PAID
        isPart -> BillStatus.PART
        tone == DueTone.OVERDUE -> BillStatus.OVERDUE
        else -> BillStatus.NONE
    }
    val captionColor = when {
        paid -> mutedColor()
        tone == DueTone.OVERDUE -> Danger
        tone == DueTone.TODAY || tone == DueTone.SOON -> Warn
        else -> mutedColor()
    }
    val kind = if (item.kind == UpcomingKind.CARD_BILL) "Bill" else "Auto-debit"
    val link = item.paymentLink
    val title = item.title
    val actions = if (paid) emptyList() else listOfNotNull(
        link?.let { RowAction("Pay bill", ButtonStyle.PRIMARY, "Pay $title") { onPay(it) } },
        RowAction("Mark as paid", ButtonStyle.OUTLINE, "Mark $title as paid") { onMarkPaid(item) }
    )
    val merchant = item.message.merchant
    PlanRow(
        title = title,
        modifier = Modifier.rowTap("Open details", onOpen),
        leading = { MerchantChip(merchant.logo, imageKey = merchant.imageKey, name = merchant.name, description = title, size = RowTile) },
        caption = {
            Caption(
                if (paid) "$kind · paid today" else "$kind · " + BillsMath.dueCaption(item.date, today),
                color = captionColor, maxLines = 2
            )
        },
        amount = { RowAmount(DisplayFormatters.rupees(item.paidAmount ?: item.amount), if (paid) Good else MaterialTheme.colorScheme.secondary) },
        more = more,
        below = {
            Column(Modifier.padding(start = RowTile + Spacing.md)) {
                BillStatusSlot(status)
                if (isPart && partPaid != null) {
                    ProgressReveal(
                        BillsMath.partFraction(item.amount, partPaid), Modifier.padding(top = Spacing.sm).clearAndSetSemantics { },
                        color = Warn, height = Spacing.xs
                    )
                    Caption(
                        "Part paid ${DisplayFormatters.rupees(partPaid)} · ${DisplayFormatters.rupees(BillsMath.left(item.amount, partPaid))} left",
                        Modifier.padding(top = Spacing.xs), color = Warn, maxLines = 2
                    )
                }
            }
        },
        actions = actions
    )
}

/** A bill already paid, in the Paid group: a check tile, the name, when it was marked, the amount, and an optional Restore. */
@Composable
internal fun PaidBillRow(title: String, caption: String, amountText: String, onRestore: (() -> Unit)?) {
    val good = Good
    PlanRow(
        title = title,
        leading = {
            Box(Modifier.size(RowTile).background(good.copy(alpha = 0.14f), AppShapes.chip), contentAlignment = Alignment.Center) {
                Text("✓", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = good)
            }
        },
        caption = { Caption(caption, maxLines = 2) },
        amount = { RowAmount(amountText, good) },
        below = { Column(Modifier.padding(start = RowTile + Spacing.md)) { BillStatusSlot(BillStatus.PAID) } },
        actions = if (onRestore != null) listOf(RowAction("Restore", ButtonStyle.TEXT, "Restore $title to the list", onRestore)) else emptyList()
    )
}
