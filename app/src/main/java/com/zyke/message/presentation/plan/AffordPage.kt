package com.zyke.message.presentation.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.forecast.UpcomingOutflows
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.insights.RecurringPayments
import com.zyke.message.domain.plan.AffordLevel
import com.zyke.message.domain.plan.AffordVerdict
import com.zyke.message.domain.plan.Affordability
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

/** A small card on Home that opens the "Can I afford it?" page. */
@Composable
internal fun AffordEntry(state: State, onIntent: (Intent) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    FintechCard(onClick = { open = true }) {
        RowTitle("Can I afford it?")
        Caption("Type a price and see how it sits with your plan, bills and balance.")
    }
    if (open) AffordPage(state, onIntent) { open = false }
}

@Composable
private fun AffordPage(state: State, onIntent: (Intent) -> Unit, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val price = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.ofInstant(state.now, zone)

    val verdict = remember(price, state.messages, state.plan, state.goalList, state.upcoming, state.loanList, state.reminderList, state.now) {
        price?.let {
            val outflows = UpcomingOutflows.build(
                from = today, days = state.cycle.daysLeft(today).toLong().coerceAtLeast(1), upcoming = state.upcoming,
                loans = state.loanList, reminders = state.reminderList, plan = state.plan,
                recurring = RecurringPayments.arrange(state.subscriptions, state.recurringStates)
            )
            Affordability.check(
                it, state.messages, state.plan, state.goalList, outflows, state.now, zone, state.cycle,
                container.settings.settings.value.lowBalanceFloor
            )
        }
    }

    fun toast(text: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
    }

    AppPage("Can I afford it?", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AmountField("Price", amount, { amount = it }, quickAdds = listOf(1000, 5000, 10000, 25000))
            FormField("What is it? (optional)", name, { name = it }, capitalization = KeyboardCapitalization.Sentences)
            if (verdict != null && price != null) {
                VerdictCard(verdict)
                val label = name.trim().ifEmpty { "Purchase" }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Add as a goal", {
                        onIntent(Intent.SaveGoal(Goal(UUID.randomUUID().toString(), label, price)))
                        toast("Added '$label' to your goals")
                    }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                    AppButton("Remind me", {
                        onIntent(Intent.SaveReminder(Reminder(UUID.randomUUID().toString(), label, price, today.plusDays(WISH_REMINDER_DAYS))))
                        toast("Reminder set for ${WISH_REMINDER_DAYS} days from now")
                    }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(verdict: AffordVerdict) {
    val tone = when (verdict.level) {
        AffordLevel.COMFORTABLE -> Tone.GOOD
        AffordLevel.TIGHT -> Tone.WARN
        AffordLevel.NOT_NOW -> Tone.BAD
        AffordLevel.CANT_TELL -> Tone.NEUTRAL
    }
    FintechCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(verdict.level.label, tone)
                RowTitle(Affordability.money(verdict.price))
            }
            verdict.reasons.forEach { reason ->
                Text(reason.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (verdict.instalments.isNotEmpty()) {
                Caption(
                    "No-cost EMI: " + verdict.instalments.joinToString(" or ") {
                        "${Affordability.money(it.perMonth)} x ${it.months} months"
                    }
                )
            }
            Caption(AffordVerdict.DISCLAIMER, Modifier.padding(top = 4.dp))
        }
    }
}

private const val WISH_REMINDER_DAYS = 7L
