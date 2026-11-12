package com.zyke.message.presentation.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.investments.Compounding
import com.zyke.message.domain.investments.Holding
import com.zyke.message.domain.investments.HoldingStatus
import com.zyke.message.domain.investments.InvestmentKind
import com.zyke.message.domain.investments.InvestmentMath
import com.zyke.message.domain.investments.InvestmentReminders
import com.zyke.message.domain.investments.Payout
import com.zyke.message.domain.investments.Portfolio
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.DateField
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.dashboard.components.LineTrend
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import java.time.LocalDate
import java.util.Locale

private fun dayText(date: LocalDate): String = FormatCache.pattern("d MMM yyyy", Locale.getDefault()).format(date)

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Caption(label, Modifier.weight(1f), maxLines = 3)
        Text(
            value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End
        )
    }
}

/** One investment in full: its worth, growth, terms and history, and what can be done with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InvestmentDetailPage(m: InvestmentsModel, id: String) {
    val h = m.find(id)
    if (h == null) {
        // Deleted (or undone away) while open: close the page after this frame.
        androidx.compose.runtime.LaunchedEffect(id) { m.ui.openId = null }
        return
    }
    val v = Portfolio.value(h, m.today)
    val onBack = { m.ui.openId = null }
    val haptics = rememberHaptics()
    AppPage(h.name, onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            FintechCard {
                CardHeader(h.kind.label + if (h.institution.isNotBlank()) " · ${h.institution}" else "")
                // The one figure of this page counts up when it opens, and to any new value after an update.
                AnimatedAmount(
                    v.current, Modifier.padding(top = Spacing.xs),
                    style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, fit = true, fromZero = true
                )
                val periodic = h.isDeposit && h.terms.payout == Payout.PERIODIC
                if (periodic) {
                    val paid = InvestmentMath.interestSoFar(h, m.today)
                    Text(
                        "Invested ${money(v.invested)}" + (paid?.let { " · Interest paid out so far about ${money(it)}" } ?: ""),
                        Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    Text(
                        investedAndGain(v.invested, v.gain, v.gainPercent, com.zyke.message.presentation.messages.components.mutedColor()),
                        Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.bodyMedium
                    )
                }
                v.cagr?.let { Caption("About ${percentText(it)} a year since ${dayText(h.startDate!!)}", Modifier.padding(top = Spacing.xs), maxLines = 2) }
                FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (v.estimated) StatusPill("Estimated", Tone.NEUTRAL)
                    v.daysToMaturity?.let { if (h.isActiveish) StatusPill(maturityPhrase(it), maturityTone(it)) }
                    if (v.stale) StatusPill("Value updated ${agoPhrase(h.valueAsOf, m.today)}", Tone.WARN)
                    if (v.valueMissing) StatusPill("No value entered yet", Tone.WARN)
                    if (h.status != HoldingStatus.ACTIVE) StatusPill(h.status.label, Tone.NEUTRAL)
                }
                if (v.estimated) {
                    Caption(
                        "Worked out from the rate and tenure. Banks and societies round and compound in their own way, so enter the figure from your statement under Update value to replace it.",
                        Modifier.padding(top = Spacing.sm), maxLines = 5
                    )
                }
            }

            if (h.isDeposit) DepositFacts(h, m.today)
            ValueHistory(h)

            FintechCard {
                CardHeader("Details")
                h.startDate?.takeIf { !h.isDeposit }?.let { Fact("Started", dayText(it)) }
                h.valueAsOf?.let { Fact("Value as of", dayText(it)) }
                if (h.receiptNo.isNotBlank()) Fact("Receipt or certificate", h.receiptNo)
                if (h.notes.isNotBlank()) Fact("Notes", h.notes)
                if (h.status == HoldingStatus.CLOSED || h.status == HoldingStatus.RENEWED) {
                    h.closedOn?.let { Fact(if (h.status == HoldingStatus.RENEWED) "Renewed on" else "Closed on", dayText(it)) }
                    h.closedAmount?.let { Fact("Amount received", money(it)) }
                }
                h.renewedFrom?.let { from -> m.find(from)?.let { Fact("Renewed from", it.name) } }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (h.status == HoldingStatus.ACTIVE || h.status == HoldingStatus.MATURED) {
                    AppButton("Update value", { m.ui.valueId = h.id }, Modifier.fillMaxWidth(), style = if (h.isDeposit) ButtonStyle.TONAL else ButtonStyle.PRIMARY)
                    if (h.isDeposit) {
                        if (h.status == HoldingStatus.ACTIVE) {
                            AppButton(
                                "Mark matured", { m.repo.markMatured(h.id)?.let { m.announce("Marked ${h.name} as matured", it) } },
                                Modifier.fillMaxWidth(), style = ButtonStyle.TONAL
                            )
                        }
                        AppButton("Renew", { m.ui.renewId = h.id }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
                    }
                    AppButton(if (h.isDeposit) "Close or redeem" else "Sell or redeem", { m.ui.closeId = h.id }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
                } else {
                    AppButton(
                        "Reopen", {
                            val undo = m.repo.update(h.copy(status = HoldingStatus.ACTIVE, closedOn = null, closedAmount = null))
                            m.announce("Reopened ${h.name}", undo)
                        },
                        Modifier.fillMaxWidth(), style = ButtonStyle.TONAL
                    )
                }
                AppButton("Edit", { m.ui.editId = h.id }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
                AppButton(
                    "Delete", {
                        haptics.reject()
                        val undo = m.repo.remove(h.id)
                        m.ui.openId = null
                        m.announce("Deleted ${h.name}", undo)
                    },
                    Modifier.fillMaxWidth(), style = ButtonStyle.TEXT
                )
            }
        }
    }
}

@Composable
private fun DepositFacts(h: Holding, today: LocalDate) {
    val maturity = InvestmentMath.maturityDate(h)
    val value = InvestmentMath.maturityValue(h)
    val given = h.terms.maturityAmountGiven != null
    val rd = h.kind == InvestmentKind.RECURRING_DEPOSIT
    FintechCard {
        CardHeader(if (rd) "Recurring deposit terms" else "Fixed deposit terms")
        h.startDate?.let { Fact("Start", dayText(it)) }
        maturity?.let { Fact("Maturity", dayText(it)) }
        InvestmentMath.tenureMonths(h)?.let { Fact("Tenure", if (it % 12 == 0) "${it / 12} ${if (it == 12) "year" else "years"}" else "$it months") }
        Fact("Interest rate", "${h.terms.ratePercent.stripTrailingZeros().toPlainString()}% a year")
        Fact("Interest is added", if (h.terms.compounding == Compounding.AT_MATURITY_AS_GIVEN) "As on the receipt" else h.terms.compounding.label)
        if (!rd) Fact("Interest", h.terms.payout.label)
        if (rd) {
            h.terms.monthlyInstallment?.let { Fact("Monthly installment", money(it)) }
            val total = InvestmentMath.tenureMonths(h)
            Fact("Installments paid", "${InvestmentMath.installmentsPaid(h, today)}" + (total?.let { " of $it" } ?: ""))
            InvestmentMath.nextInstallment(h, today)?.let { Fact("Next installment", dayText(it)) }
            InvestmentMath.totalDeposit(h)?.let { Fact("Total deposit at the end", money(it)) }
        }
        value?.let { Fact(if (given) "Maturity amount (from your receipt)" else "Maturity amount (estimated)", money(it)) }
        InvestmentMath.periodicPayout(h)?.let { Fact("Interest paid out each period", money(it)) }
        InvestmentMath.interestAtMaturity(h)?.takeIf { it.signum() > 0 }?.let { Fact("Interest over the term", money(it)) }
        if (h.terms.payout == Payout.CUMULATIVE) InvestmentMath.interestSoFar(h, today)?.takeIf { h.currentValue == null }?.let { Fact("Interest so far (estimated)", money(it)) }
        InvestmentReminders.build(listOf(h), today).firstOrNull()?.let { Fact("Next reminder", dayText(it.date)) }
            ?: if (h.status == HoldingStatus.ACTIVE) Fact("Reminders", "None coming up") else Unit
    }
}

@Composable
private fun ValueHistory(h: Holding) {
    val points = h.history
    if (points.size < 2) return
    FintechCard {
        CardHeader("Value over time")
        val first = points.first()
        val last = points.last()
        LineTrend(
            points.map { it.value }, dayText(first.date), dayText(last.date),
            "Value of ${h.name} from ${money(first.value)} on ${dayText(first.date)} to ${money(last.value)} on ${dayText(last.date)}",
            Modifier.padding(top = Spacing.sm)
        )
        HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
        points.takeLast(5).reversed().forEach { Fact(dayText(it.date), money(it.value)) }
    }
}

// ---- small sheets

/** The current value and the day it was true; it is added to the history, and shown at once everywhere. */
@Composable
internal fun UpdateValueSheet(m: InvestmentsModel, h: Holding) {
    var valueText by rememberSaveable { mutableStateOf((h.currentValue ?: Portfolio.value(h, m.today).current.takeIf { !h.isDeposit })?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf(m.today.toString()) }
    var investedText by rememberSaveable { mutableStateOf(h.invested.stripTrailingZeros().toPlainString()) }
    val value = valueText.toBigDecimalOrNull()
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: m.today
    val invested = investedText.toBigDecimalOrNull()
    val onDismiss = { m.ui.valueId = null }
    FormSheet(
        title = "Update value of ${h.name}",
        onDismiss = onDismiss,
        primaryLabel = "Save value",
        primaryEnabled = value != null && value.signum() >= 0 && (h.isDeposit || (invested != null && invested.signum() > 0)),
        hint = "Enter what it is worth now",
        onPrimary = {
            val undo = m.repo.updateValue(h.id, value!!, date, if (h.isDeposit) null else invested)
            m.ui.valueId = null
            if (undo != null) m.announce("Updated ${h.name}", undo)
        },
        secondaryLabel = if (h.isDeposit && h.currentValue != null) "Go back to the estimate" else null,
        onSecondary = {
            val undo = m.repo.update(h.copy(currentValue = null, valueAsOf = null))
            m.ui.valueId = null
            m.announce("${h.name} is estimated again", undo)
        }
    ) {
        MoneyField(
            if (h.isDeposit) "Value today (replaces the estimate)" else "Current value", valueText, { valueText = it },
            supporting = if (h.isDeposit) "From your statement or passbook" else "What it is worth today"
        )
        DateField("As of", date, { dateText = (it ?: m.today).toString() }, presets = listOf("Today" to m.today))
        if (!h.isDeposit) {
            MoneyField("Amount invested so far", investedText, { investedText = it }, supporting = "Change it if you have added more money")
        }
    }
}

/** Marks the holding closed: what was received and when. It moves to the Closed list and its reminders stop. */
@Composable
internal fun CloseSheet(m: InvestmentsModel, h: Holding) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(m.today.toString()) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: m.today
    val amount = amountText.toBigDecimalOrNull()
    FormSheet(
        title = if (h.isDeposit) "Close ${h.name}" else "Sell ${h.name}",
        onDismiss = { m.ui.closeId = null },
        primaryLabel = "Close it",
        primaryEnabled = amountText.isBlank() || (amount != null && amount.signum() >= 0),
        hint = "Enter a valid amount, or leave it empty",
        onPrimary = {
            val undo = m.repo.close(h.id, amount, date)
            m.ui.closeId = null
            m.ui.openId = null
            if (undo != null) m.announce("Closed ${h.name}", undo)
        }
    ) {
        MoneyField("Amount received (optional)", amountText, { amountText = it }, supporting = "It moves to Closed and no longer counts in your total")
        DateField("Received on", date, { dateText = (it ?: m.today).toString() }, presets = listOf("Today" to m.today))
    }
}
