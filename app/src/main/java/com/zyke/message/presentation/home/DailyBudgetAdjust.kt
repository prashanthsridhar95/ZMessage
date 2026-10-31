package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.budget.DailyAllowance
import com.zyke.message.domain.budget.DailyBasis
import com.zyke.message.domain.budget.DailyBudgetStatus
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import java.math.BigDecimal
import java.math.RoundingMode

/** Where the figure on the card comes from, in a few words (no amounts, so it is safe with amounts hidden). */
internal fun basisCaption(basis: DailyBasis, cyclesUsed: Int): String = when (basis) {
    DailyBasis.CUSTOM -> "Set by you"
    DailyBasis.PLAN -> "From your plan"
    DailyBasis.TREND -> when {
        cyclesUsed >= 2 -> "Based on your last $cyclesUsed months"
        cyclesUsed == 1 -> "Based on last month"
        else -> "Based on this month so far"
    }
}

private fun explanation(suggestion: DailyAllowance?): String = when (suggestion?.basis) {
    DailyBasis.PLAN -> "Your plan sets aside your regular payments, investments and savings, and shares what is left to spend over the days of the month."
    DailyBasis.TREND -> {
        val span = when {
            suggestion.cyclesUsed >= 2 -> "your last ${suggestion.cyclesUsed} months"
            suggestion.cyclesUsed == 1 -> "last month"
            else -> "this month so far"
        }
        "Worked out from what you spent in $span: the typical daily spend, so one unusual month does not move it. " +
            "Investments, savings, insurance, charity, card bills, regular payments and money you set aside are left out."
    }
    else -> "There is not enough spending yet to suggest one: it needs about a month. Type a daily amount to use meanwhile."
}

/** Change today's budget: the amount, quick picks around the suggestion, how the suggestion was worked out, and a way back to it. */
@Composable
internal fun AdjustDailySheet(
    current: DailyBudgetStatus?,
    suggestion: DailyAllowance?,
    onSave: (BigDecimal) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current?.allowance?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    val amount = text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val base = suggestion?.amount ?: current?.allowance
    val picks = base?.let { b ->
        listOf(
            "−10%" to b.multiply(BigDecimal("0.9")),
            "Suggested" to b,
            "+10%" to b.multiply(BigDecimal("1.1"))
        ).map { (name, value) -> name to value.divide(BigDecimal.TEN, 0, RoundingMode.FLOOR).multiply(BigDecimal.TEN) }
    }.orEmpty().filter { it.second.signum() > 0 }

    FormSheet(
        title = "Daily budget",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = amount != null,
        onPrimary = { amount?.let(onSave) },
        secondaryLabel = if (suggestion != null) "Use suggested" else null,
        onSecondary = if (suggestion != null) onReset else null,
        hint = "Enter an amount above zero"
    ) {
        AmountField("Spend per day", text, { text = it })
        if (picks.isNotEmpty()) {
            FormGroup("Quick picks") {
                Chips(
                    picks.map { (name, value) ->
                        ChipOption("$name ${DisplayFormatters.rupees(value)}", amount != null && amount.compareTo(value) == 0)
                    },
                    onSelect = { text = picks[it].second.toPlainString() }
                )
            }
        }
        Column(Modifier.fillMaxWidth()) {
            suggestion?.let {
                PrivateAmount(
                    "Suggested ${DisplayFormatters.rupees(it.amount)} a day",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                )
            }
            Caption(explanation(suggestion), Modifier.padding(top = 4.dp), maxLines = 6)
        }
    }
}
