package com.zyke.message.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.insights.CancelGuides
import com.zyke.message.domain.insights.CancelledCharge
import com.zyke.message.domain.insights.CostShare
import com.zyke.message.domain.insights.HintKind
import com.zyke.message.domain.insights.SubscriptionHint
import com.zyke.message.domain.insights.SubscriptionLine
import com.zyke.message.domain.insights.SubscriptionOverview
import com.zyke.message.domain.insights.SubscriptionSort
import com.zyke.message.domain.insights.SubscriptionState
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.HIDDEN_AMOUNT
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.SegmentedTabs
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.openExternally
import com.zyke.message.presentation.common.rememberGrowingState
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.dashboard.components.MoneyLine
import com.zyke.message.presentation.dashboard.components.money
import com.zyke.message.presentation.dashboard.components.words
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.AmountText
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val DayMonth = FormatCache.pattern("d MMM", Locale.getDefault())

private fun plural(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"

/** What the chips filter by; [state] null is "All". */
private enum class SubFilter(val label: String, val state: SubscriptionState?) {
    ACTIVE("Active", SubscriptionState.ACTIVE),
    PAUSED("Paused", SubscriptionState.PAUSED),
    CANCELLED("Cancelled", SubscriptionState.CANCELLED),
    ALL("All", null)
}

private val SORTS = SubscriptionSort.values().toList()

/**
 * Every charge that comes round: first anything charged after it was cancelled, then what the active ones cost in a
 * year (with the biggest five as a bar), then the list, filtered by chips and sorted by one of three orders. A row
 * keeps its "Cancel" / "Still using it" button; price rises and other questions fold into one "Check" chip.
 */
@Composable
internal fun SubscriptionsPage(state: State, onIntent: (Intent) -> Unit, onBack: () -> Unit) {
    val overview = state.subscriptionOverview
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val handler = rememberPlanActionHandler(onIntent)
    var cancellingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    // A local copy of the lines, so the sheet looks the row up in what the page shows.
    val lines = overview.lines
    val cancelling = cancellingKey?.let { key -> lines.firstOrNull { it.key == key } }
    val charges = state.review.cancelledCharges

    fun stillUsing(key: String, name: String) {
        onIntent(Intent.RestoreSubscription(key))
        handler.announce("$name is back in your subscriptions") { onIntent(Intent.CancelSubscription(key, today)) }
    }

    AppPage("Subscriptions", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (overview.isEmpty) {
                EmptyState(
                    "No subscriptions found",
                    "A payment that comes round every month, to the same place for about the same amount, is listed here once three have arrived."
                )
            } else {
                if (charges.isNotEmpty()) {
                    ChargedAfterCancelling(
                        charges,
                        onOpen = { onIntent(Intent.OpenMessage(it.message.id)) },
                        onStillUsing = { charge -> stillUsing(charge.key, charge.name) }
                    )
                }
                SubscriptionsHero(overview)

                val filters = remember(overview) {
                    SubFilter.values().filter { it == SubFilter.ALL || (it.state != null && overview.count(it.state) > 0) }
                }
                val filter = filters.firstOrNull { it.name == picked } ?: filters.first()
                FilterRow(filters, filter, overview) { picked = it.name }
                SegmentedTabs(SORTS.map { it.label }, sortIndex, { sortIndex = it })
                val rows = remember(overview, filter, sortIndex) { overview.view(filter.state, SORTS[sortIndex]) }
                FintechCard {
                    rows.forEachIndexed { index, line ->
                        key(line.key) {
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            SubscriptionLineRow(
                                line, today, showState = filter == SubFilter.ALL,
                                onCancel = { cancellingKey = line.key },
                                onStillUsing = { stillUsing(line.key, line.name) },
                                onOpenMessage = { onIntent(Intent.OpenMessage(line.subscription.messageId)) }
                            )
                        }
                    }
                }
                Caption(
                    "Found from the payments in your messages, so it can miss one or include one that is not. Only active ones are counted in the total.",
                    maxLines = 4
                )
            }
        }
    }

    cancelling?.let { line ->
        CancelSheet(
            line,
            onDismiss = { cancellingKey = null },
            onCancelled = {
                cancellingKey = null
                onIntent(Intent.CancelSubscription(line.key, today))
                handler.announce("Marked ${line.name} as cancelled") { onIntent(Intent.RestoreSubscription(line.key)) }
            }
        )
    }
}

/** A charge that came after the person said they had cancelled: the one thing here that may need action today. */
@Composable
private fun ChargedAfterCancelling(charges: List<CancelledCharge>, onOpen: (CancelledCharge) -> Unit, onStillUsing: (CancelledCharge) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    FintechCard {
        Text(
            "Charged after you cancelled", Modifier.heading(),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error
        )
        Caption("Worth a look before anything else. Was this expected?", Modifier.padding(top = 2.dp), maxLines = 2)
        charges.forEachIndexed { index, charge ->
            key(charge.reviewKey) {
                if (index > 0) HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                val on = DayMonth.format(Instant.ofEpochMilli(charge.message.message.date).atZone(zone).toLocalDate())
                val cancelledOn = DayMonth.format(charge.cancelledOn)
                val amount = DisplayFormatters.rupees(charge.amount)
                val spoken = if (LocalPrivacyMode.current.hidden) "${charge.name} charged on $on after you cancelled it on $cancelledOn, amount hidden"
                else "${charge.name} charged $amount on $on after you cancelled it on $cancelledOn"
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) { contentDescription = spoken },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        RowTitle(charge.name)
                        Caption("Charged $on \u00b7 you marked it cancelled on $cancelledOn", maxLines = 3)
                    }
                    Spacer(Modifier.width(8.dp))
                    AmountText(amount, MaterialTheme.colorScheme.error)
                }
                ActionButtons(
                    listOf(
                        RowAction("Open message", ButtonStyle.TONAL, "Open the ${charge.name} charge") { onOpen(charge) },
                        RowAction("Still using it", ButtonStyle.OUTLINE, "I still use ${charge.name}, bring it back") { onStillUsing(charge) }
                    ),
                    Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionsHero(overview: SubscriptionOverview) {
    val count = overview.activeCount
    val yearly = DisplayFormatters.rupees(overview.yearlyTotal)
    val monthly = DisplayFormatters.rupees(overview.monthlyTotal)
    val worthALook = overview.lines.count { it.counts && it.hints.isNotEmpty() }
    val shares = remember(overview) { overview.costShares(5) }
    FintechCard {
        if (count == 0) {
            Caption("NOTHING ACTIVE")
            Text(
                "No active subscriptions", Modifier.padding(top = 4.dp).heading(),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
            )
        } else {
            Caption("YOU PAY ABOUT")
            val hidden = LocalPrivacyMode.current.hidden
            val spoken = if (hidden) "About a year on ${plural(count, "subscription", "subscriptions")}, amounts hidden"
            else "About $yearly a year on ${plural(count, "subscription", "subscriptions")}, about $monthly a month"
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Bottom) {
                    Text("\u2248", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    PrivateAmount(
                        yearly, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1
                    )
                    Text("a year", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = mutedColor())
                }
                MoneyLine(
                    listOf(words("${plural(count, "subscription", "subscriptions")} \u00b7 about"), money(monthly), words("a month")),
                    Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = mutedColor(), moneyColor = mutedColor()
                )
            }
            if (shares.size > 1) CostBar(shares, Modifier.padding(top = 14.dp))
            if (worthALook > 0) {
                Caption(
                    "${if (worthALook == 1) "1 is" else "$worthALook are"} worth a second look",
                    Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** The biggest few as one stacked bar, so the weight of each shows at a glance; a legend names them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CostBar(shares: List<CostShare>, modifier: Modifier = Modifier) {
    val grown by rememberGrowingState(1f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val colors = shares.mapIndexed { i, s -> if (s.line == null) ChartColors.other else ChartColors.forRank(i) }
    val total = remember(shares) { shares.fold(java.math.BigDecimal.ZERO) { sum, s -> sum + s.yearly }.toFloat().coerceAtLeast(1f) }
    val spoken = "Share of the yearly cost: " + shares.joinToString(", ") { "${it.line?.name ?: "Others"} ${it.percent} percent" }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Canvas(Modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics { }) {
            val gap = 2.dp.toPx()
            val usable = size.width - gap * (shares.size - 1)
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(track, Offset.Zero, size, radius)
            var x = 0f
            shares.forEachIndexed { i, s ->
                val w = (usable * (s.yearly.toFloat() / total) * grown).coerceAtLeast(0f)
                if (w > 0f) drawRoundRect(colors[i], Offset(x, 0f), Size(w, size.height), CornerRadius(minOf(radius.x, w / 2f)))
                x += w + gap
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shares.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(8.dp).height(8.dp).background(colors[i], RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(6.dp))
                    Caption("${s.line?.name ?: "Others"} ${s.percent}%")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(filters: List<SubFilter>, selected: SubFilter, overview: SubscriptionOverview, onSelect: (SubFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        filters.forEach { f ->
            val n = if (f.state == null) overview.lines.size else overview.count(f.state)
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text("${f.label} $n", maxLines = 1) },
                modifier = Modifier.semantics { contentDescription = "${f.label}, ${plural(n, "subscription", "subscriptions")}" }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionLineRow(
    line: SubscriptionLine,
    today: LocalDate,
    showState: Boolean,
    onCancel: () -> Unit,
    onStillUsing: () -> Unit,
    onOpenMessage: () -> Unit
) {
    val sub = line.subscription
    val hidden = LocalPrivacyMode.current.hidden
    val cancelled = line.state == SubscriptionState.CANCELLED
    var checking by rememberSaveable(line.key) { mutableStateOf(false) }
    val actions = buildList {
        if (cancelled) {
            add(RowAction("Still using it", ButtonStyle.TONAL, "I still use ${line.name}, bring it back") { onStillUsing() })
        } else {
            add(RowAction("Cancel", ButtonStyle.TONAL, "How to cancel ${line.name}") { onCancel() })
        }
        add(RowAction("Last charge", ButtonStyle.TEXT, "Open the last ${line.name} charge, ${DisplayFormatters.rupees(sub.amount).takeIf { !hidden } ?: "amount hidden"}") { onOpenMessage() })
    }
    PlanRow(
        title = line.name,
        leading = {
            MerchantChip(
                line.merchant?.logo ?: MerchantLogo.UPI, size = 44.dp, imageKey = line.merchant?.imageKey, name = line.name, description = null
            )
        },
        caption = {
            Caption(line.cadence.label + " \u00b7 " + if (cancelled) "cancelled ${line.cancelledOn?.let { DayMonth.format(it) }.orEmpty()}" else nextLabel(line, today), maxLines = 2)
        },
        amount = {
            Column(horizontalAlignment = Alignment.End) {
                AmountText(DisplayFormatters.rupees(line.yearly), if (cancelled) mutedColor() else MaterialTheme.colorScheme.primary)
                Caption("a year")
            }
        },
        actions = actions,
        below = {
            val hasCheck = line.hints.isNotEmpty() && !cancelled
            if (showState || hasCheck) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (showState) StatusPill(line.state.label, stateTone(line.state))
                    if (hasCheck) CheckChip(line.hints.size, checking, line.name) { checking = !checking }
                }
            }
            if (hasCheck && checking) {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.hints.forEach { hint ->
                        Column {
                            Text(hintLabel(hint.kind), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = toneColor(Tone.WARN))
                            Caption(hintText(hint, hidden), maxLines = 4)
                        }
                    }
                }
            }
        }
    )
}

/** One quiet chip for everything worth a second look; it unfolds the questions in place. */
@Composable
private fun CheckChip(count: Int, expanded: Boolean, name: String, onToggle: () -> Unit) {
    val label = if (count > 1) "Check \u00b7 $count" else "Check"
    val action = if (expanded) "Hide what to check for $name" else "Show what to check for $name"
    Row(
        Modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusPill(label, Tone.WARN)
        Spacer(Modifier.width(4.dp))
        Text(if (expanded) "\u25B4" else "\u25BE", style = MaterialTheme.typography.labelMedium, color = mutedColor())
    }
}

private fun hintLabel(kind: HintKind) = when (kind) {
    HintKind.PRICE_UP -> "Price went up"
    HintKind.NEW -> "New"
    HintKind.SIMILAR -> "Similar service"
}

private fun stateTone(state: SubscriptionState) = when (state) {
    SubscriptionState.ACTIVE -> Tone.GOOD
    SubscriptionState.PAUSED -> Tone.WARN
    SubscriptionState.CANCELLED -> Tone.NEUTRAL
}

private fun nextLabel(line: SubscriptionLine, today: LocalDate): String =
    if (line.state == SubscriptionState.PAUSED) "no charge since ${DayMonth.format(line.subscription.lastPaid)}"
    else "next ~${DayMonth.format(line.subscription.nextDue)}"

/** Hint wording with any rupee amount taken out in privacy mode. */
private fun hintText(hint: SubscriptionHint, hidden: Boolean): String =
    if (hidden) hint.text.replace(Regex("\u20b9[\\d,.]+"), HIDDEN_AMOUNT) else hint.text

/**
 * How to stop it: steps for the way it is paid, the details to quote, one-tap links that need no permission, and
 * "I've cancelled it", which marks the subscription cancelled (with Undo) and watches for a charge that comes after.
 */
@Composable
private fun CancelSheet(line: SubscriptionLine, onDismiss: () -> Unit, onCancelled: () -> Unit) {
    val guide = remember(line.key, line.channel) { CancelGuides.guide(line.channel, line.name) }
    val reference = remember(line) { CancelGuides.reference(line) }
    val hidden = LocalPrivacyMode.current.hidden
    val context = LocalContext.current
    AdaptiveSheet(
        title = "Cancel ${line.name}",
        onDismiss = onDismiss,
        subtitle = "Looks like it is paid by ${line.channel.label}",
        contentSpacing = 12.dp,
        footer = {
            AppButton("I've cancelled it", onCancelled, Modifier.fillMaxWidth())
            Caption("Zyke Message cannot cancel for you. Mark it once you have, and it will tell you if a charge still comes.", maxLines = 3)
        }
    ) {
        Text(guide.title, Modifier.heading(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        guide.steps.forEachIndexed { index, step -> GuideStep(index + 1, step) }
        Caption(guide.tip, maxLines = 4)

        if (guide.offersPlayLink) {
            // Handed to the phone's browser or Play Store: this app makes no request of its own.
            AppButton(
                "Open Google Play subscriptions",
                { openExternally(context, CancelGuides.PLAY_SUBSCRIPTIONS_URL) },
                Modifier.fillMaxWidth().semantics { contentDescription = "Open the Google Play subscriptions page" },
                style = ButtonStyle.TONAL, size = ButtonSize.COMPACT
            )
        }

        Text("Details to quote", Modifier.heading(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FintechCard {
            Text(
                if (hidden) reference.replace(Regex("₹[\\d,.]+"), HIDDEN_AMOUNT) else reference,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
            )
        }
        Caption("These come from the payments, never from the text of your messages.", maxLines = 2)
        AppButton(
            "Copy details",
            {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Subscription details", reference))
                Toast.makeText(context, "Details copied", Toast.LENGTH_SHORT).show()
            },
            Modifier.fillMaxWidth().semantics { contentDescription = "Copy the details of ${line.name} to the clipboard" },
            style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
        )
    }
}

@Composable
private fun GuideStep(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        Text(
            "$number.", Modifier.width(24.dp).clearAndSetSemantics { },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
        Text(
            text,
            Modifier.weight(1f).semantics { contentDescription = "Step $number: $text" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
        )
    }
}
