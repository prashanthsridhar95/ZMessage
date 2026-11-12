package com.zyke.message.presentation.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.investments.HoldingValue
import com.zyke.message.domain.investments.InvestmentKind
import com.zyke.message.domain.investments.Maturity
import com.zyke.message.domain.investments.Portfolio
import com.zyke.message.domain.plan.PlanActionState
import com.zyke.message.domain.plan.PlanActions
import com.zyke.message.domain.plan.PlanKeys
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.home.PlanRow
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

internal fun money(value: BigDecimal): String = DisplayFormatters.rupees(value)

/** "+₹25,000": a sign always, so a gain or loss never depends on colour alone. */
internal fun signedMoney(value: BigDecimal): String = when {
    value.signum() > 0 -> "+" + money(value)
    value.signum() < 0 -> "−" + money(value.negate())
    else -> money(value)
}

internal fun percentText(percent: Double, signed: Boolean = true): String {
    val text = String.format(Locale.US, "%.1f%%", Math.abs(percent))
    return when {
        !signed -> text
        percent > 0.05 -> "+$text"
        percent < -0.05 -> "−$text"
        else -> text
    }
}

/** "+₹25,000 (+25.0%)" */
internal fun gainText(gain: BigDecimal, percent: Double?): String =
    signedMoney(gain) + (percent?.let { " (${percentText(it)})" } ?: "")

@Composable
internal fun gainColor(gain: BigDecimal): Color = when {
    gain.signum() > 0 -> com.zyke.message.presentation.theme.AppColors.success
    gain.signum() < 0 -> com.zyke.message.presentation.theme.AppColors.danger
    else -> MaterialTheme.colorScheme.secondary
}

/** "Invested ₹1,00,000 · Gain +₹25,000 (+25.0%)" with the gain coloured. */
@Composable
internal fun investedAndGain(invested: BigDecimal, gain: BigDecimal, percent: Double?, muted: Color): AnnotatedString {
    val color = gainColor(gain)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = muted)) { append("Invested ${money(invested)} · ${if (gain.signum() < 0) "Loss" else "Gain"} ") }
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(gainText(gain, percent)) }
    }
}

/** Kind colours: the chart palette, so an allocation bar and a row's chip agree. */
internal fun kindColor(kind: InvestmentKind): Color = when (kind) {
    InvestmentKind.FIXED_DEPOSIT -> ChartColors.forRank(0)
    InvestmentKind.RECURRING_DEPOSIT -> ChartColors.forRank(4)
    InvestmentKind.MUTUAL_FUND -> ChartColors.forRank(2)
    InvestmentKind.STOCKS -> ChartColors.forRank(1)
    InvestmentKind.GOLD -> ChartColors.forRank(3)
    InvestmentKind.BONDS -> ChartColors.forRank(0)
    InvestmentKind.PPF_NPS -> ChartColors.forRank(2)
    InvestmentKind.OTHER -> ChartColors.other
}

/** A rounded tile with the kind's short name ("FD", "MF"): drawn here, so no logo is ever fetched for a fund society. */
@Composable
internal fun InvestmentChip(kind: InvestmentKind, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val color = kindColor(kind)
    Box(
        modifier.size(size).background(color.copy(alpha = 0.18f), AppShapes.chip),
        contentAlignment = Alignment.Center
    ) {
        Text(kind.short.take(5), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** A pill that can be tapped, with a 48dp touch target around it. */
@Composable
internal fun ActionPill(text: String, tone: Tone, description: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).tappable(onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.CenterStart
    ) { StatusPill(text, tone) }
}

/** "Matures in 128 days", "Matures tomorrow", "Matured 3 days ago". */
internal fun maturityPhrase(days: Long): String = when {
    days > 1 -> "Matures in $days days"
    days == 1L -> "Matures tomorrow"
    days == 0L -> "Matures today"
    days == -1L -> "Matured yesterday"
    else -> "Matured ${-days} days ago"
}

internal fun maturityTone(days: Long): Tone = if (days <= 7) Tone.WARN else Tone.NEUTRAL

/** How old a value is: "today", "yesterday", "45 days ago". */
internal fun agoPhrase(date: LocalDate?, today: LocalDate): String {
    if (date == null) return "never"
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0 -> "today"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

/** One line for a screen reader: the same words with amounts hidden while amounts are hidden. */
@Composable
internal fun rowSpeech(v: HoldingValue, today: LocalDate): String {
    val h = v.holding
    val parts = ArrayList<String>()
    parts += "${h.name}, ${h.kind.label}" + if (h.institution.isNotBlank()) ", ${h.institution}" else ""
    parts += "worth ${money(v.current)}" + if (v.estimated) ", estimated" else ""
    parts += "invested ${money(v.invested)}"
    if (v.invested.signum() > 0) parts += (if (v.gain.signum() < 0) "loss " else "gain ") + money(v.gain.abs())
    v.daysToMaturity?.let { parts += maturityPhrase(it) }
    if (v.stale) parts += "value updated ${agoPhrase(h.valueAsOf, today)}"
    return com.zyke.message.presentation.common.privateSpeech(parts.joinToString(". "))
}

/** The deposits maturing within two weeks on Home's Plans card, not dismissed or snoozed. */
@Composable
fun rememberMaturitiesAhead(hides: PlanActionState, today: LocalDate): List<Maturity> {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val state by container.investments.state.collectAsState()
    return remember(state.holdings, hides, today) {
        PlanActions.visible(Portfolio.maturingSoon(state.holdings, today), hides, today) { PlanKeys.maturity(it) }
    }
}

/** "FD matures · name · ₹X" as a row on Home's Plans card. */
@Composable
internal fun MaturityRow(item: Maturity, onOpen: () -> Unit, more: (@Composable () -> Unit)?) {
    val h = item.holding
    val amount = item.value
    val label = "${h.kind.short} matures · ${h.name}" + (amount?.let { " · ${money(it)}" } ?: "")
    PlanRow(
        title = label,
        modifier = Modifier.fullWidthClickable(onClickLabel = "Open investment", onClick = onOpen),
        leading = { InvestmentChip(h.kind) },
        caption = { Caption(listOf(h.institution.takeIf { it.isNotBlank() }, maturityPhrase(item.days)).filterNotNull().joinToString(" · "), maxLines = 2) },
        more = more
    )
}
