package com.zyke.message.presentation.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.investments.Holding
import com.zyke.message.domain.investments.HoldingRepository
import com.zyke.message.domain.investments.HoldingStatus
import com.zyke.message.domain.investments.HoldingUndo
import com.zyke.message.domain.investments.HoldingValue
import com.zyke.message.domain.investments.InvestmentKind
import com.zyke.message.domain.investments.InvestmentMath
import com.zyke.message.domain.investments.Portfolio
import com.zyke.message.domain.investments.PortfolioSummary
import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.revealOnce
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.itemMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole
import com.zyke.message.presentation.theme.tonalFill
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.tour.tourAnchor
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

private val BAR_HEIGHT = 10.dp

/** What the person has open on the Investments tab. Plain state, kept apart from the numbers so it survives their changes. */
@Stable
internal class InvestmentsUiState {
    var filter by mutableStateOf<InvestmentKind?>(null)
    var addKind by mutableStateOf<InvestmentKind?>(null)
    var openId by mutableStateOf<String?>(null)
    var editId by mutableStateOf<String?>(null)
    var renewId by mutableStateOf<String?>(null)
    var valueId by mutableStateOf<String?>(null)
    var closeId by mutableStateOf<String?>(null)
    var closedShown by mutableStateOf(false)
}

/** The numbers and the actions the tab and its pages share. Every change announces itself with an Undo. */
@Stable
internal class InvestmentsModel(
    val repo: HoldingRepository,
    val summary: PortfolioSummary,
    val holdings: List<Holding>,
    val dismissed: Set<String>,
    val today: LocalDate,
    val ui: InvestmentsUiState,
    val announce: (String, HoldingUndo?) -> Unit
) {
    val institutions: List<String> = Portfolio.institutions(holdings)
    fun find(id: String?): Holding? = holdings.firstOrNull { it.id == id }
}

/** [providentFund]: the balance of the provident fund(s) read from messages, counted in the same total as net worth does. */
@Composable
internal fun rememberInvestmentsModel(providentFund: BigDecimal?, today: LocalDate): InvestmentsModel {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repo = container.investments
    val state by repo.state.collectAsState()
    val ui = remember { InvestmentsUiState() }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val announce: (String, HoldingUndo?) -> Unit = remember(repo, snackbar, scope) {
        { message, undo ->
            scope.launch {
                val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed && undo != null) repo.undo(undo)
            }
        }
    }
    val summary = remember(state.holdings, providentFund, today) { Portfolio.summarize(state.holdings, providentFund, today) }
    return remember(summary, state.maturityDismissed, ui, announce) {
        InvestmentsModel(repo, summary, state.holdings, state.maturityDismissed, today, ui, announce)
    }
}

/** Copies the net-worth entries of investment kinds into the tracker once, so each is counted in one place only. */
@Composable
internal fun MigrateNetWorthEntries() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val items by container.netWorth.items.collectAsState()
    LaunchedEffect(items) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { container.investments.migrate(items) }
    }
}

/** A reminder notification, or a row on Home, asks for Investments (and maybe one holding): the tab is shown and the holding opened. */
@Composable
internal fun HandleInvestmentRequests(model: InvestmentsModel, showTab: () -> Unit) {
    val request = InvestmentRequests.pending.value
    LaunchedEffect(request) {
        if (request != null) {
            showTab()
            request.holdingId?.let { id -> if (model.find(id) != null) model.ui.openId = id }
            InvestmentRequests.pending.value = null
        }
    }
}

// ---- list

private fun dayText(date: LocalDate): String = FormatCache.pattern("d MMM yyyy", Locale.getDefault()).format(date)

/**
 * The Investments tab: matured deposits that need a decision, the add button, how the money is spread, and every holding
 * grouped by kind. The provident fund tiles read from messages come after, from [providentFundTiles].
 */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.investmentItems(
    m: InvestmentsModel,
    extraActions: List<com.zyke.message.presentation.common.ActionSpec>,
    /** The sub-tab switch's fade/slide, put on each item (nothing when the tab was not just switched). */
    itemModifier: Modifier = Modifier,
    providentFundTiles: LazyListScope.() -> Unit
) {
    val s = m.summary
    val overdue = s.overdue.filter { it.id !in m.dismissed }.take(3)
    overdue.forEach { h -> item(key = "matured:" + h.id) { MaturedCard(m, h, itemMotion().then(itemModifier)) } }

    item(key = "inv-add") {
        // The same round-action row as the other two sub-tabs: Add first, then what else this tab can do right now.
        val closedAction = if (s.closed.isNotEmpty() && m.ui.filter == null) listOf(
            com.zyke.message.presentation.common.ActionSpec(
                if (m.ui.closedShown) "Hide closed" else "Show closed", com.zyke.message.R.drawable.baseline_archive_24,
                { m.ui.closedShown = !m.ui.closedShown }
            )
        ) else emptyList()
        com.zyke.message.presentation.common.ActionCircleRow(
            listOf(
                com.zyke.message.presentation.common.ActionSpec(
                    "Add investment", com.zyke.message.R.drawable.ic_tile_add, { m.ui.addKind = InvestmentKind.FIXED_DEPOSIT },
                    primary = true, description = "Add an investment"
                )
            ) + extraActions + closedAction,
            itemModifier.tourAnchor(TourAnchor.INVESTMENTS_ADD)
        )
    }

    if (s.isEmpty) {
        item(key = "inv-empty") {
            Column(itemModifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                EmptyState(
                    "No investments yet",
                    "Add a deposit, fund, stock or gold. A receipt is enough, no message needed.",
                    icon = com.zyke.message.R.drawable.ic_trending_24
                )
                // Three equal tiles in one row (one under another on a narrow screen or at large text), never 2 + 1.
                com.zyke.message.presentation.common.ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.sm) {
                    listOf(InvestmentKind.FIXED_DEPOSIT, InvestmentKind.RECURRING_DEPOSIT, InvestmentKind.MUTUAL_FUND).forEach { kind ->
                        KindTile(kind.label, { m.ui.addKind = kind }, Modifier.cell())
                    }
                }
            }
        }
    } else {
        if (s.totalValue.signum() > 0 && s.allocation.isNotEmpty()) {
            item(key = "inv-allocation") { AllocationCard(m, itemMotion().then(itemModifier)) }
        }
        InvestmentKind.entries.forEach { kind ->
            val rows = s.rows.filter { it.holding.kind == kind && (m.ui.filter == null || m.ui.filter == kind) }
            if (rows.isNotEmpty()) {
                item(key = "inv-head:" + kind.name) { Box(itemMotion().then(itemModifier)) { SectionHeader(kind.label, rows.size) } }
                rows.forEach { v -> item(key = "holding:" + v.holding.id) { HoldingRow(m, v, itemMotion().then(itemModifier)) } }
            }
        }
        if (s.closed.isNotEmpty() && m.ui.filter == null) {
            item(key = "inv-closed-head") {
                Row(
                    itemMotion().then(itemModifier).fillMaxWidth().heightIn(min = 48.dp).tappable({ m.ui.closedShown = !m.ui.closedShown })
                        .semantics { contentDescription = (if (m.ui.closedShown) "Hide" else "Show") + " closed and renewed investments" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) { SectionHeader("Closed and renewed", s.closed.size) }
                    Text(if (m.ui.closedShown) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (m.ui.closedShown) s.closed.forEach { h -> item(key = "holding:" + h.id) { ClosedRow(m, h, itemMotion().then(itemModifier)) } }
        }
    }
    providentFundTiles()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaturedCard(m: InvestmentsModel, h: Holding, modifier: Modifier = Modifier) {
    val on = InvestmentMath.maturityDate(h)
    val value = InvestmentMath.maturityValue(h)
    FintechCard(modifier) {
        CardHeader("Needs a decision")
        Text(
            "${h.kind.short} matured: renew, close or keep?", Modifier.padding(top = Spacing.xs),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
        )
        Caption(
            listOfNotNull(h.name, h.institution.takeIf { it.isNotBlank() }, on?.let { "matured ${dayText(it)}" }, value?.let { money(it) }).joinToString(" · "),
            Modifier.padding(top = Spacing.xs), maxLines = 3
        )
        FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AppButton("Renew", { m.ui.renewId = h.id }, size = ButtonSize.COMPACT)
            AppButton("Close", { m.ui.closeId = h.id }, size = ButtonSize.COMPACT, style = ButtonStyle.TONAL)
            AppButton(
                "Keep", { m.repo.markMatured(h.id)?.let { m.announce("Kept ${h.name} as matured", it) } },
                size = ButtonSize.COMPACT, style = ButtonStyle.TONAL
            )
            AppButton("Not now", { m.repo.dismissMaturityCard(h.id) }, size = ButtonSize.COMPACT, style = ButtonStyle.TEXT)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocationCard(m: InvestmentsModel, modifier: Modifier = Modifier) {
    val s = m.summary
    val spoken = privateSpeech(
        "Allocation: " + s.allocation.joinToString(", ") { "${it.label} ${Math.round(it.share * 100)} percent" } +
            (m.ui.filter?.let { ". Showing only ${it.label}" } ?: "")
    )
    FintechCard(modifier) {
        CardHeader("Where it is")
        // The bar grows into place once; the chips under it say the same in words.
        Row(
            Modifier.padding(top = Spacing.sm).fillMaxWidth().height(BAR_HEIGHT).clip(CircleShape).revealOnce().semantics { contentDescription = spoken }
        ) {
            s.allocation.forEach { slice ->
                val color = slice.kind?.let { kindColor(it) } ?: com.zyke.message.presentation.dashboard.components.ChartColors.other
                Box(Modifier.weight(slice.share.toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(color))
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            s.allocation.forEach { slice ->
                val kind = slice.kind
                val selected = kind != null && m.ui.filter == kind
                val color = kind?.let { kindColor(it) } ?: com.zyke.message.presentation.dashboard.components.ChartColors.other
                Row(
                    Modifier.heightIn(min = 48.dp)
                        .then(if (kind != null) Modifier.tappable({ m.ui.filter = if (selected) null else kind }, tick = true) else Modifier)
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, AppShapes.chip)
                        .padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(Spacing.sm).background(color, CircleShape))
                    Text(
                        " ${slice.label} ${Math.round(slice.share * 100)}%" + if (selected) " ✓" else "",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        if (m.ui.filter != null) Caption("Tap the highlighted one again to show everything", maxLines = 2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoldingRow(m: InvestmentsModel, v: HoldingValue, modifier: Modifier = Modifier) {
    val h = v.holding
    val speech = rowSpeech(v, m.today)
    FintechCard(modifier.semantics(mergeDescendants = true) { contentDescription = speech }, onClick = { m.ui.openId = h.id }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InvestmentChip(h.kind)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                RowTitle(h.name)
                Caption(listOf(h.institution.takeIf { it.isNotBlank() }, h.kind.label).filterNotNull().joinToString(" · "), maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                // A new value (an update, a refreshed price) counts to its figure; the first showing is still.
                AnimatedAmount(
                    v.current, style = TypeRole.title, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1
                )
                if (v.invested.signum() > 0 && !(h.isDeposit && h.terms.payout == com.zyke.message.domain.investments.Payout.PERIODIC)) {
                    Text(
                        gainText(v.gain, v.gainPercent), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = gainColor(v.gain), maxLines = 1
                    )
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            v.daysToMaturity?.let { days ->
                if (h.status == HoldingStatus.MATURED) StatusPill("Matured · decide", Tone.WARN) else StatusPill(maturityPhrase(days), maturityTone(days))
            }
            if (v.estimated) StatusPill("Estimated", Tone.NEUTRAL)
            if (v.valueMissing) {
                ActionPill("Value not updated · Add", Tone.WARN, "Add the current value of ${h.name}") { m.ui.valueId = h.id }
            } else if (v.stale) {
                ActionPill("Value updated ${agoPhrase(h.valueAsOf, m.today)} · Update", Tone.WARN, "Update the value of ${h.name}") { m.ui.valueId = h.id }
            }
        }
    }
}

@Composable
private fun ClosedRow(m: InvestmentsModel, h: Holding, modifier: Modifier = Modifier) {
    FintechCard(modifier, onClick = { m.ui.openId = h.id }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InvestmentChip(h.kind)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                RowTitle(h.name)
                Caption(
                    listOfNotNull(h.status.label, h.closedOn?.let { dayText(it) }, h.closedAmount?.let { "received ${money(it)}" }).joinToString(" · "),
                    maxLines = 2
                )
            }
            StatusPill(h.status.label, Tone.NEUTRAL)
        }
    }
}

/** Newly made successor id. */
internal fun newHoldingId(): String = UUID.randomUUID().toString()


/** One of the three common things to add: an even tile with a centred label that may use two lines. */
@Composable
private fun KindTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 56.dp)
            .clip(AppShapes.control)
            .background(tonalFill(Tonal.Quiet))
            .tappable(onClick, onClickLabel = "Add $label")
            .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2
        )
    }
}
