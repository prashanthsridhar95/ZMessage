package com.zyke.message.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.insights.CancelGuides
import com.zyke.message.domain.insights.HintKind
import com.zyke.message.domain.insights.SubscriptionHint
import com.zyke.message.domain.insights.SubscriptionLine
import com.zyke.message.domain.insights.SubscriptionOverview
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
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.openExternally
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.AmountText
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.mutedColor
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val DayMonth = FormatCache.pattern("d MMM", Locale.getDefault())

private fun plural(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"

/**
 * Every charge that comes round, with what it costs in a year, where it stands, a flag when the price went up, a few
 * modest questions worth asking, and a way to stop it. Only the active ones are in the total.
 */
@Composable
internal fun SubscriptionsPage(state: State, onIntent: (Intent) -> Unit, onBack: () -> Unit) {
    val overview = state.subscriptionOverview
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val handler = rememberPlanActionHandler(onIntent)
    var cancellingKey by rememberSaveable { mutableStateOf<String?>(null) }
    // A local copy of the lines, so the sheet looks the row up in what the page shows.
    val lines = overview.lines
    val cancelling = cancellingKey?.let { key -> lines.firstOrNull { it.key == key } }

    AppPage("Subscriptions", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (overview.isEmpty) {
                EmptyState(
                    "No subscriptions found",
                    "A payment that comes round every month, to the same place for about the same amount, is listed here once three have arrived."
                )
            } else {
                SubscriptionsHero(overview)
                listOf(
                    "Active" to SubscriptionState.ACTIVE,
                    "Paused" to SubscriptionState.PAUSED,
                    "Cancelled" to SubscriptionState.CANCELLED
                ).forEach { (title, group) ->
                    val rows = lines.filter { it.state == group }
                    if (rows.isNotEmpty()) {
                        SectionHeader(title, rows.size)
                        FintechCard {
                            rows.forEachIndexed { index, line ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                SubscriptionLineRow(
                                    line, today,
                                    onCancel = { cancellingKey = line.key },
                                    onStillUsing = {
                                        onIntent(Intent.RestoreSubscription(line.key))
                                        handler.announce("${line.name} is back in your subscriptions") {
                                            onIntent(Intent.CancelSubscription(line.key, today))
                                        }
                                    },
                                    onOpenMessage = { onIntent(Intent.OpenMessage(line.subscription.messageId)) }
                                )
                            }
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

@Composable
private fun SubscriptionsHero(overview: SubscriptionOverview) {
    val count = overview.activeCount
    val yearly = DisplayFormatters.rupees(overview.yearlyTotal)
    val monthly = DisplayFormatters.rupees(overview.monthlyTotal)
    val worthALook = overview.lines.count { it.counts && it.hints.isNotEmpty() }
    FintechCard {
        if (count == 0) {
            Caption("NOTHING ACTIVE")
            Text(
                "No active subscriptions", Modifier.padding(top = 4.dp).heading(),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
            )
        } else {
            Caption("YOU PAY ABOUT")
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                PrivateAmount(
                    yearly, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
                Text("a year", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = mutedColor())
            }
            Caption("on ${plural(count, "subscription", "subscriptions")}", Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption("That is about ")
                PrivateAmount(monthly, style = MaterialTheme.typography.bodySmall, color = mutedColor(), maxLines = 1)
                Caption(" a month")
            }
            if (worthALook > 0) {
                Caption(
                    "${if (worthALook == 1) "1 is" else "$worthALook are"} worth a second look",
                    Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionLineRow(
    line: SubscriptionLine,
    today: LocalDate,
    onCancel: () -> Unit,
    onStillUsing: () -> Unit,
    onOpenMessage: () -> Unit
) {
    val sub = line.subscription
    val hidden = LocalPrivacyMode.current.hidden
    val cancelled = line.state == SubscriptionState.CANCELLED
    val amount = DisplayFormatters.rupees(sub.amount)
    val actions = buildList {
        if (cancelled) {
            add(RowAction("Still using it", ButtonStyle.OUTLINE, "I still use ${line.name}, bring it back") { onStillUsing() })
        } else {
            add(RowAction("Cancel", ButtonStyle.PRIMARY, "How to cancel ${line.name}") { onCancel() })
        }
        add(RowAction("Last charge", ButtonStyle.OUTLINE, "Open the last ${line.name} charge") { onOpenMessage() })
    }
    PlanRow(
        title = line.name,
        leading = {
            MerchantChip(
                line.merchant?.logo ?: MerchantLogo.UPI, size = 44.dp, imageKey = line.merchant?.imageKey, name = line.name, description = null
            )
        },
        caption = {
            Caption(line.cadence.label + " · " + if (cancelled) "cancelled ${line.cancelledOn?.let { DayMonth.format(it) }.orEmpty()}" else nextLabel(line, today), maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Last ")
                PrivateAmount(amount, style = MaterialTheme.typography.bodySmall, color = mutedColor(), maxLines = 1)
                Caption(" on ${DayMonth.format(sub.lastPaid)}", maxLines = 1)
            }
        },
        amount = {
            Column(horizontalAlignment = Alignment.End) {
                AmountText(DisplayFormatters.rupees(line.yearly), if (cancelled) mutedColor() else MaterialTheme.colorScheme.secondary)
                Caption("a year")
            }
        },
        actions = actions,
        below = {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(line.state.label, stateTone(line.state))
                val change = line.priceChange
                if (change != null && !cancelled) {
                    StatusPill(
                        if (hidden) "Price went up" else "Price went up ${DisplayFormatters.rupees(change.from)} → ${DisplayFormatters.rupees(change.to)}",
                        Tone.WARN
                    )
                }
            }
            line.hints.filter { it.kind != HintKind.PRICE_UP }.forEach { hint ->
                Caption(hintText(hint, hidden), Modifier.padding(top = 6.dp), maxLines = 3)
            }
            if (line.priceChange != null && !cancelled && line.hints.any { it.kind == HintKind.PRICE_UP }) {
                Caption("Did you expect that?", Modifier.padding(top = 6.dp))
            }
        }
    )
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
    if (hidden) hint.text.replace(Regex("₹[\\d,.]+"), HIDDEN_AMOUNT) else hint.text

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
