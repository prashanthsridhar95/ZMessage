package com.zyke.message.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.accounts.AccountsOverviewModel
import com.zyke.message.domain.accounts.CardDue
import com.zyke.message.domain.accounts.CardDuesSummary
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.headerContentColor
import com.zyke.message.presentation.common.headerMutedColor
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole
import com.zyke.message.presentation.theme.tonalFill
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/*
 * The Overview sub-tab of Accounts (docs/DESIGN.md, "Accounts: Overview"): the total balance as the hero, what the credit cards
 * will ask for (billed, unbilled, both together) as three tiles, a few quiet lines of context, and links to the other sub-tabs.
 * Net worth is deliberately not here: it opens only when the person pulls down at the top (NetWorthPull) or asks for it.
 */

/** What the quiet line under the header offers for net worth. */
internal enum class NetWorthHint { NONE, PULL, BUTTON }

/** The tiles keep one height when their notes differ in length. */
private val TILE_MIN_HEIGHT = 116.dp

/** "₹1,234", or "−₹1,234" for less than nothing (the platform formatter would put the sign after the symbol). */
internal fun signedRupees(amount: BigDecimal): String =
    if (amount.signum() < 0) "−" + DisplayFormatters.rupees(amount.negate()) else DisplayFormatters.rupees(amount)

/**
 * The Overview header: the bank total, large. It is one node for a screen reader, which also gets "Show net worth" as an action
 * (a pull cannot be done there); [onShowNetWorth] runs the same lock check as the pull.
 */
@Composable
internal fun OverviewHeaderContent(
    model: AccountsOverviewModel,
    /** The total counts up from zero (the first showing of this sub-tab). */
    entering: Boolean,
    onShowNetWorth: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            customActions = listOf(CustomAccessibilityAction("Show net worth") { onShowNetWorth(); true })
        }
    ) {
        HeaderLabel("Total balance")
        val total = model.totalBalance
        if (total != null) {
            AnimatedAmount(
                total, Modifier.padding(top = Spacing.xs), style = TypeRole.hero,
                color = headerContentColor(), maxLines = 1, fit = true, fromZero = entering
            )
            Text(DisplayFormatters.count(model.countedBalances, "bank account", "bank accounts"), style = TypeRole.caption, color = headerMutedColor())
        } else {
            Text("Your accounts", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineMedium, color = headerContentColor())
            Text("No balances yet", style = TypeRole.caption, color = headerMutedColor())
        }
    }
}

/** The Overview body under the header: the net worth hint, the card dues, the quiet lines, the links. */
internal fun LazyListScope.overviewBodyItems(
    model: AccountsOverviewModel,
    today: LocalDate,
    gate: EnterGate,
    hint: NetWorthHint,
    bankCount: Int,
    cardCount: Int,
    onShowNetWorth: () -> Unit,
    onOpenDues: () -> Unit,
    onGoTo: (Int) -> Unit,
    /** The sub-tab switch's fade/slide, put on each item (nothing when the tab was not just switched). */
    itemModifier: Modifier = Modifier
) {
    val dues = model.dues
    when (hint) {
        NetWorthHint.NONE -> Unit
        NetWorthHint.PULL -> item(key = "ov-hint") {
            Caption("Pull down to see net worth", itemModifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        NetWorthHint.BUTTON -> item(key = "ov-hint") {
            Box(itemModifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppButton("Show net worth", onShowNetWorth, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
    if (!dues.hasCards) {
        item(key = "ov-empty") {
            EmptyState(
                "No cards yet", "Billed and unbilled appear when your bank texts you about a card.",
                itemModifier.enterOnce(gate, 0), icon = R.drawable.ic_card_24
            )
        }
    } else {
        item(key = "ov-dues") { DuesTiles(dues, onOpenDues, itemModifier.enterOnce(gate, 0)) }
        val left = model.leftAfterDues
        val next = dues.next
        if (left != null || next != null) {
            item(key = "ov-glance") { GlanceCard(left, next, today, itemModifier.enterOnce(gate, 1)) }
        }
    }
    item(key = "ov-links") {
        OverviewLinks(bankCount, cardCount, onGoTo, itemModifier.enterOnce(gate, 2))
    }
}

/** Billed and Unbilled side by side (stacked on a narrow phone or at large text), then both together; a tap opens the per-card sheet. */
@Composable
private fun DuesTiles(dues: CardDuesSummary, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        CardHeader("Credit card dues")
        ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.md) {
            DueTile(
                "Billed", dues.billed, if (dues.billed.signum() > 0) "On the latest statements" else "Nothing to pay now",
                onOpen, Modifier.cell()
            )
            DueTile(
                "Unbilled", dues.unbilled, if (dues.approx) "Approx. this month's spending" else "Spent since the last statements",
                onOpen, Modifier.cell()
            )
        }
        DueTile(
            "Total card dues", dues.total, if (dues.approx) "Billed plus unbilled, partly estimated" else "Billed plus unbilled",
            onOpen, Modifier, level = Tonal.Raised
        )
    }
}

@Composable
private fun DueTile(label: String, amount: BigDecimal, note: String, onOpen: () -> Unit, modifier: Modifier, level: Tonal = Tonal.Quiet) {
    FintechCard(modifier.heightIn(min = TILE_MIN_HEIGHT), onClick = onOpen, level = level) {
        Caption(label.uppercase(), maxLines = 2)
        AnimatedAmount(
            amount, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true
        )
        Caption(note, Modifier.padding(top = Spacing.xs), maxLines = 3)
    }
}

/** What is left once the cards are paid, and the bill that falls due next. */
@Composable
private fun GlanceCard(left: BigDecimal?, next: com.zyke.message.domain.accounts.NextDue?, today: LocalDate, modifier: Modifier = Modifier) {
    FintechCard(modifier) {
        CardHeader("At a glance")
        if (left != null) {
            GlanceRow(
                "Left after card dues", "Balance minus billed and unbilled", signedRupees(left),
                if (left.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        if (next != null) {
            val day = FormatCache.pattern("d MMM", Locale.getDefault()).format(next.date)
            GlanceRow(
                "Next bill due", "${next.title} · ${if (next.overdue) "was due" else "due"} $day", DisplayFormatters.rupees(next.amount),
                if (next.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun GlanceRow(label: String, caption: String, value: String, valueColor: Color) {
    ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.xs) {
        Column(Modifier.cell()) {
            RowTitle(label, color = MaterialTheme.colorScheme.onSurface)
            Caption(caption, maxLines = 2)
        }
        PrivateAmount(
            value, Modifier.cell(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = valueColor, maxLines = 1, fit = true, textAlign = if (stacked) TextAlign.Start else TextAlign.End
        )
    }
}

/** Chips to the other sub-tabs; they wrap onto a second line rather than squeeze. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewLinks(bankCount: Int, cardCount: Int, onGoTo: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        LinkChip("Accounts · $bankCount") { onGoTo(1) }
        LinkChip("Cards · $cardCount") { onGoTo(2) }
        LinkChip("Investments") { onGoTo(3) }
    }
}

@Composable
private fun LinkChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).tappable(onClick = onClick, onClickLabel = "Open $text")
            .background(tonalFill(Tonal.Quiet), AppShapes.chip).padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** The per-card breakdown behind the tiles: each card with its billed and unbilled, soonest bill first. */
@Composable
internal fun CardDuesSheet(dues: CardDuesSummary, today: LocalDate, onOpenCard: (String) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet("Card dues", onDismiss, subtitle = "By card, soonest bill first", contentSpacing = Spacing.md) {
        dues.cards.forEach { card ->
            FintechCard(onClick = { onOpenCard(card.key) }) {
                RowTitle(card.title, color = MaterialTheme.colorScheme.onSurface)
                Caption("•••• ${card.last4} · ${dueText(card, today)}", maxLines = 2)
                ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.md) {
                    DueStat("Billed", card.billed, if (card.billPaid) "Paid" else null, Modifier.cell())
                    DueStat("Unbilled", card.unbilled, if (card.unbilledApprox) "Approx." else null, Modifier.cell())
                }
            }
        }
        FintechCard(level = Tonal.Raised) {
            CardHeader("Total")
            ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.md) {
                DueStat("Billed", dues.billed, null, Modifier.cell())
                DueStat("Unbilled", dues.unbilled, if (dues.approx) "Approx." else null, Modifier.cell())
            }
            AnimatedAmount(
                dues.total, Modifier.padding(top = Spacing.md), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true
            )
            Caption("Together", maxLines = 1)
        }
    }
}

@Composable
private fun DueStat(label: String, amount: BigDecimal, note: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Caption(label.uppercase())
        PrivateAmount(
            DisplayFormatters.rupees(amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, fit = true
        )
        if (note != null) Caption(note, maxLines = 1)
    }
}

private fun dueText(card: CardDue, today: LocalDate): String {
    val due = card.dueDate
    return when {
        card.billed.signum() > 0 && due != null -> DisplayFormatters.dueLabel(due, today)
        card.billed.signum() > 0 -> "Bill to pay"
        card.billPaid -> "Bill paid"
        card.statementTotal == null -> "No bill yet"
        else -> "Nothing to pay"
    }
}
