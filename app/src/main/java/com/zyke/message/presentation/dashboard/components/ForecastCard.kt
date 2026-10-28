package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.AppBottomSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.forecast.CutReason
import com.zyke.message.domain.forecast.CutSuggestion
import com.zyke.message.domain.forecast.SpendForecast
import com.zyke.message.presentation.common.limitColors
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where the cycle is heading at the current pace: what the bank accounts will hold when it ends, whether a credit
 * card would be needed to get there, and the categories where spending less would help most.
 */
@Composable
internal fun ForecastCard(forecast: SpendForecast, modifier: Modifier = Modifier) {
    val colors = limitColors()
    var open by rememberSaveable { mutableStateOf(false) }
    val end = forecast.endBalance
    val short = forecast.cardNeeded != null

    // On the page it is one tile: the answer in a line. The working-out is in the sheet it opens.
    FintechCard(modifier, onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption("WHERE THIS IS HEADING")
                Text(
                    when {
                        end == null -> "Spending about ${DisplayFormatters.rupees(forecast.bankPerDay + forecast.cardPerDay)} a day"
                        short -> "Short by about ${DisplayFormatters.rupees(forecast.cardNeeded!!)}"
                        else -> "About ${DisplayFormatters.rupees(end)} left at the end"
                    },
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (short) colors.bad else if (end != null) colors.good else MaterialTheme.colorScheme.primary
                )
                Caption(
                    if (short) "You would need a credit card for the rest" else "If spending carries on as it has",
                    Modifier.padding(top = 2.dp), maxLines = 2
                )
            }
            Text("\u203A", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }

    if (open) {
        AppBottomSheet({ open = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)
            ) {
                Text(
                    "Where this is heading",
                    Modifier.heading().padding(bottom = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                ForecastDetails(forecast)
            }
        }
    }
}

@Composable
private fun ForecastDetails(forecast: SpendForecast) {
    val colors = limitColors()
    val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val end = forecast.endBalance

    if (end != null) {
        val short = forecast.cardNeeded != null
        Text(
            if (short) "Your balance falls short by about ${DisplayFormatters.rupees(forecast.cardNeeded!!)}"
            else "You'll end the cycle with about ${DisplayFormatters.rupees(end)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (short) colors.bad else colors.good
        )
        Caption("By ${dayFormat.format(forecast.cycleEnd)}, if spending carries on as it has", maxLines = 2)

        if (short) {
            Text(
                "You'd have to use your credit card for about ${DisplayFormatters.rupees(forecast.cardNeeded!!)}" +
                    (forecast.runsOutOn?.let { " from around ${dayFormat.format(it)}" } ?: ""),
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    } else {
        Text(
            "Spending about ${DisplayFormatters.rupees(forecast.bankPerDay + forecast.cardPerDay)} a day",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Caption("Bank balances are not known yet, so the end balance cannot be worked out", maxLines = 2)
    }

    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    forecast.balanceNow?.let { Line("In your accounts now", DisplayFormatters.rupees(it)) }
    if (forecast.expectedIncome.signum() > 0) Line("Salary expected", "+ ${DisplayFormatters.rupees(forecast.expectedIncome)}")
    Line("Everyday spending, ${forecast.daysLeft} more days", "− ${DisplayFormatters.rupees(forecast.projectedBankSpend)}")
    if (forecast.billsDue.signum() > 0) Line("Bills and auto-debits due", "− ${DisplayFormatters.rupees(forecast.billsDue)}")
    if (forecast.projectedCardSpend.signum() > 0) {
        Caption(
            "On your cards, about ${DisplayFormatters.rupees(forecast.projectedCardSpend)} more will be added to the next bill",
            Modifier.padding(top = 6.dp), maxLines = 3
        )
    }

    if (forecast.suggestions.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Text("Where you could cut back", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        forecast.suggestions.forEach { Suggestion(it) }
        Caption(
            "Doing all of these keeps about ${DisplayFormatters.rupees(forecast.totalSaving)} by the end of the cycle",
            Modifier.padding(top = 10.dp), maxLines = 2
        )
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Suggestion(cut: CutSuggestion) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RowTitle(cut.category.displayString)
            Caption(
                when (cut.reason) {
                    CutReason.ABOVE_USUAL ->
                        "On pace for ${DisplayFormatters.rupees(cut.projected)}, usually ${DisplayFormatters.rupees(cut.usual!!)}. Keep it to your usual."
                    CutReason.BIGGEST_SHARE ->
                        "On pace for ${DisplayFormatters.rupees(cut.projected)}, one of your biggest. Trim it by about a sixth."
                },
                maxLines = 3
            )
        }
        Text(
            "Save ${DisplayFormatters.rupees(cut.saving)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = limitColors().good
        )
    }
}
