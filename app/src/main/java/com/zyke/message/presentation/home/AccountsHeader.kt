package com.zyke.message.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import com.zyke.message.presentation.common.DeckState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.accounts.AccountInsights
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.accounts.AccountsOverview
import com.zyke.message.domain.balance.BalanceCheckFlow
import com.zyke.message.domain.balance.BalanceRequests
import com.zyke.message.domain.balance.CheckState
import com.zyke.message.domain.cards.CardSpend
import com.zyke.message.domain.cards.CardSpendView
import com.zyke.message.presentation.common.ActionCircleRow
import com.zyke.message.presentation.common.ActionSpec
import com.zyke.message.presentation.common.CardFace
import com.zyke.message.presentation.common.LimitTone
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.SpendCard
import com.zyke.message.presentation.common.StackedDeck
import com.zyke.message.presentation.common.headerTileColor
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.faceCard
import com.zyke.message.presentation.common.headerContentColor
import com.zyke.message.presentation.common.headerMutedColor
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.rememberIsEntering
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/*
 * The header regions and bodies of the three Accounts sub-tabs. The look (HeaderRegion, StackedDeck, ActionCircleRow, SpendCard,
 * CategoryBar) lives in presentation/common; this file only says what goes in them for cards, bank accounts and investments.
 */

/**
 * True on the first showing of a sub-tab's hero (its figure then counts up from zero) and false on every later visit. Call it only
 * where real figures are on screen (never in a skeleton), or the count would be spent on a placeholder.
 */
@Composable
internal fun EnterGate.heroEntering(): Boolean {
    val entering = rememberIsEntering(0)
    LaunchedEffect(Unit) { played = true }
    return entering
}

/** The small spaced label at the top of a header region ("TOTAL BALANCE"), in the region's own muted ink. */
@Composable
private fun HeaderLabel(text: String) {
    Text(text.uppercase(), Modifier.heading(), style = TypeRole.section, color = headerMutedColor(), maxLines = 1)
}

/**
 * The name and the "Credit card · •••• 5580" line of the card in front, crossfading when the front card changes (keyed by the
 * card), with the overflow menu beside them. The menu stays put and always speaks for the card in front.
 */
@Composable
private fun FrontTitle(frontKey: String, lines: (String) -> Pair<String, String>, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        CrossfadeContent(frontKey, Modifier.weight(1f), label = "front card") { key ->
            val (title, line) = lines(key)
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = TypeRole.title, fontWeight = FontWeight.SemiBold, color = headerContentColor(), maxLines = 2)
                Text(line, style = TypeRole.caption, color = headerMutedColor(), maxLines = 2)
            }
        }
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides headerContentColor()) { menu() }
    }
}

/** What the Cards header holds: the stacked deck, then the name and the "Credit card · •••• 5580" line of the card in front. */
@Composable
internal fun CardsHeaderContent(
    cards: List<AccountSummary>,
    pager: DeckState,
    today: LocalDate,
    onOpen: (AccountSummary) -> Unit,
    onRename: (AccountSummary) -> Unit,
    onLeaveOut: (AccountSummary) -> Unit,
    deckModifier: Modifier = Modifier
) {
    if (cards.isEmpty()) {
        HeaderLabel("Cards")
        Text("Your cards", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineMedium, color = headerContentColor())
        Text("No cards yet", style = TypeRole.caption, color = headerMutedColor())
        return
    }
    val faces = remember(cards) { cards.map { it.faceCard() } }
    StackedDeck(cards.size, { cards[it].key }, pager, deckModifier) { index ->
        CardFace(faces[index], footer = cardFooter(cards[index], today), onClick = { onOpen(cards[index]) })
    }
    val front = cards[com.zyke.message.domain.cards.DeckModel.clamp(pager.currentPage, cards.size)]
    FrontTitle(front.key, { key -> (cards.firstOrNull { it.key == key } ?: front).let { it.title to cardKindLine(it) } }) {
        AccountActions(front.title, isCard = true, { onOpen(front) }, { onRename(front) }, { onLeaveOut(front) })
    }
}

/** The line a bank account face shows under its balance. */
private fun freshness(account: AccountSummary, today: LocalDate): String {
    val asOf = account.balanceAsOf ?: return "No balance yet"
    return if (account.balanceAdjustment != null && account.movementCount > 0) {
        "As of ${AccountInsights.age(asOf, today)} · ${DisplayFormatters.count(account.movementCount, "payment", "payments")} since"
    } else "As of ${AccountInsights.age(asOf, today)}"
}

/** The Accounts header: the total, then one big tile per bank account in a stacked deck. */
@Composable
internal fun BanksHeaderContent(
    overview: AccountsOverview,
    banks: List<AccountSummary>,
    pager: DeckState,
    today: LocalDate,
    onOpen: (AccountSummary) -> Unit,
    onRename: (AccountSummary) -> Unit,
    onLeaveOut: (AccountSummary) -> Unit,
    onCheckBalance: (AccountSummary) -> Unit,
    onBalanceSettings: (AccountSummary) -> Unit,
    /** The total counts up from zero (the first showing of this sub-tab). */
    entering: Boolean = false,
    deckModifier: Modifier = Modifier
) {
    HeaderLabel("Total balance")
    val total = overview.totalBalance
    if (total != null) {
        AnimatedAmount(
            total, Modifier.padding(top = Spacing.xs), style = TypeRole.hero,
            color = headerContentColor(), maxLines = 1, fit = true, fromZero = entering
        )
        Text(DisplayFormatters.count(overview.countedBalances, "bank account", "bank accounts"), style = TypeRole.caption, color = headerMutedColor())
    } else {
        Text("Your accounts", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineMedium, color = headerContentColor())
        Text("No balances yet", style = TypeRole.caption, color = headerMutedColor())
    }
    if (banks.isEmpty()) return
    Spacer(Modifier.height(Spacing.lg))
    StackedDeck(banks.size, { banks[it].key }, pager, deckModifier) { index ->
        val account = banks[index]
        val balance = account.balance
        Column(
            Modifier.fillMaxWidth().background(headerTileColor(0.10f), AppShapes.card).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountChip(account, TILE_CHIP)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(account.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = headerContentColor(), maxLines = 2)
                    Text("${account.kind.label} •••• ${account.last4}", style = TypeRole.caption, color = headerMutedColor(), maxLines = 1)
                }
            }
            // A new balance (a check by text, a message) counts to its figure; a first showing is still.
            if (balance != null) {
                AnimatedAmount(
                    balance, Modifier.padding(top = Spacing.md),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = headerContentColor(), maxLines = 1, fit = true
                )
            } else {
                PrivateAmount(
                    "—", Modifier.padding(top = Spacing.md),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = headerContentColor(), maxLines = 1, fit = true
                )
            }
            Text(freshness(account, today), style = TypeRole.caption, color = headerMutedColor(), maxLines = 2)
        }
    }
    val front = banks[com.zyke.message.domain.cards.DeckModel.clamp(pager.currentPage, banks.size)]
    FrontTitle(front.key, { key -> (banks.firstOrNull { it.key == key } ?: front).let { it.title to "${it.kind.label} · •••• ${it.last4}" } }) {
        AccountActions(front.title, isCard = false, { onOpen(front) }, { onRename(front) }, { onLeaveOut(front) }, { onCheckBalance(front) }, { onBalanceSettings(front) })
    }
}

private val TILE_CHIP = 40.dp

/**
 * The Accounts body: Check balance (one tap once the bank was confirmed, with its progress in the circle), Details, Set balance;
 * what the account took in and paid out this month; and its latest activity.
 */
internal fun LazyListScope.bankBodyItems(
    account: AccountSummary,
    messages: List<com.zyke.message.domain.model.ClassifiedMessage>,
    today: LocalDate,
    zone: ZoneId,
    onOpen: () -> Unit,
    onSetBalance: () -> Unit,
    onCheckBalance: () -> Unit,
    /** The sub-tab switch's fade/slide, put on each item (nothing when the tab was not just switched). */
    itemModifier: Modifier = Modifier
) {
    item(key = "bank-actions-" + account.key) {
        val (state, progress) = rememberCheckState(account.key, account.balanceAt)
        val tap = rememberCheckTap(BalanceTarget(account.key, account.bankName, account.bankLabel, account.last4, isProvidentFund = false), onCheckBalance)
        val checking = state == CheckState.CHECKING
        Column(itemModifier.fillMaxWidth()) {
            ActionCircleRow(
                listOf(
                    ActionSpec(
                        BalanceCheckFlow.label(state) ?: "Check balance",
                        if (state == CheckState.UPDATED) R.drawable.baseline_check_24 else R.drawable.baseline_message_24,
                        tap, primary = true, enabled = !checking, progress = if (checking) progress else null,
                        description = if (checking) "Checking the balance" else "Check the balance of ${account.title} by text"
                    ),
                    ActionSpec("Details", R.drawable.ic_info_24, onOpen),
                    ActionSpec(if (account.balance == null) "Set balance" else "Fix balance", R.drawable.ic_edit_24, onSetBalance)
                )
            )
            // The note under the circles changes with the check (sent, no reply yet, failed): it fades and the column eases to its size.
            CrossfadeContent(BalanceCheckFlow.note(state), Modifier.fillMaxWidth(), label = "balance check note") { note ->
                if (note != null) Caption(note, Modifier.padding(top = Spacing.xs).fillMaxWidth(), maxLines = 2)
            }
            if (checking) RichReplyHint(BalanceRequests.RICH_REPLY_HINT)
        }
    }
    item(key = "bank-spend-" + account.key) {
        val view = remember(messages, account.last4, today) { CardSpend.view(messages, account.last4, null, today, zone, isCard = false) }
        InOutCard(view, itemModifier)
    }
    item(key = "bank-recent-" + account.key) {
        val view = remember(messages, account.last4, today) { CardSpend.view(messages, account.last4, null, today, zone, isCard = false) }
        RecentList("Recent activity", view.recent, "No payments on this account yet.", onOpen, itemModifier)
    }
}

/** "Spent this month" for a bank account: what went out, with what came in beside it and how much of it that is. */
@Composable
private fun InOutCard(view: CardSpendView, modifier: Modifier = Modifier) {
    val received = view.received
    val share = if (received.signum() > 0) (view.spent.toDouble() / received.toDouble()) else null
    SpendCard(
        modifier = modifier,
        title = "Spent this month",
        amount = DisplayFormatters.rupees(view.spent),
        limitLabel = "Came in",
        limit = received.takeIf { it.signum() > 0 }?.let { DisplayFormatters.rupees(it) },
        progress = share?.toFloat(),
        tone = if (share != null && share > 1.0) LimitTone.OVER else LimitTone.OK,
        note = share?.let { "Spent ${(it * 100).toInt()}% of what came in" },
        shares = view.shares,
        emptyText = "Nothing spent from this account this month yet."
    )
}

/** The Investments header: the total, what was put in and the growth, all in the region's own colours. */
@Composable
internal fun InvestmentsHeaderContent(
    summary: com.zyke.message.domain.investments.PortfolioSummary,
    fundCount: Int,
    /** The total counts up from zero (the first showing of this sub-tab). */
    entering: Boolean = false
) {
    val hasAny = summary.count > 0 || summary.providentFund != null
    HeaderLabel("Total investments")
    if (hasAny) {
        AnimatedAmount(
            summary.totalValue, Modifier.padding(top = Spacing.xs), style = TypeRole.hero,
            color = headerContentColor(), maxLines = 1, fit = true, fromZero = entering
        )
        if (summary.count > 0) {
            Text(
                com.zyke.message.presentation.investments.investedAndGain(summary.totalInvested, summary.gain, summary.gainPercent, headerMutedColor()),
                Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.bodyMedium, maxLines = 3
            )
        } else {
            Text(DisplayFormatters.count(fundCount, "fund", "funds"), style = TypeRole.caption, color = headerMutedColor())
        }
    } else {
        Text("Your investments", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineMedium, color = headerContentColor())
        Text("None yet. Add a deposit or a fund.", style = TypeRole.caption, color = headerMutedColor(), maxLines = 2)
    }
}
