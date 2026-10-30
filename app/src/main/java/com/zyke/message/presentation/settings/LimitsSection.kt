package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.fullWidthClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.CategoryLimits
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LimitStatus
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal

private const val MAX_SUGGESTIONS = 5

/**
 * Daily and monthly spending limits per category, with where each one stands: available in green, over in red.
 * This is the only place limits are set; the Money tab only shows how they are going and links here.
 */
@Composable
internal fun LimitsSection(
    limits: List<CategoryLimits>,
    onSave: (MessageCategory, daily: String, monthly: String) -> Unit,
    onRemove: (MessageCategory) -> Unit,
    spendable: BigDecimal? = null,
    suggestions: List<com.zyke.message.domain.forecast.LimitSuggestion> = emptyList(),
    cardSpend: com.zyke.message.domain.accounts.CardSpendStatus? = null,
    onCardLimit: (String) -> Unit = {}
) {
    var editing by remember { mutableStateOf<CategoryLimits?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingCardLimit by remember { mutableStateOf(false) }

    SectionCard("Spending limits", summary = if (limits.isEmpty()) "None set" else DisplayFormatters.count(limits.size, "limit", "limits")) {
        Caption("Per day, per month, or both")

        // Worked out from the plan: what is left to spend, shared out by what is usually spent.
        if (suggestions.isNotEmpty()) {
            Column(Modifier.padding(top = 14.dp)) {
                Text("Suggested for your plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Caption(
                    spendable?.let { "Your plan leaves about ${DisplayFormatters.rupees(it)} a month to spend. These fit within it." }
                        ?: "A little under what you usually spend. Fill in your money plan to fit them to your income.",
                    Modifier.padding(top = 2.dp), maxLines = 3
                )
                suggestions.take(MAX_SUGGESTIONS).forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            RowTitle(s.category.displayString)
                            Caption("Usually ${DisplayFormatters.rupees(s.usual)}" + (s.current?.let { " \u00B7 now ${DisplayFormatters.rupees(it)}" } ?: ""))
                        }
                        Text(DisplayFormatters.rupees(s.suggested), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        AppButton(
                            "Set",
                            { onSave(s.category, limits.firstOrNull { it.category == s.category }?.daily?.limit?.stripTrailingZeros()?.toPlainString().orEmpty(), s.suggested.toPlainString()) },
                            style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                        )
                    }
                }
                AppButton(
                    "Set all suggested",
                    {
                        suggestions.forEach { s ->
                            onSave(s.category, limits.firstOrNull { it.category == s.category }?.daily?.limit?.stripTrailingZeros()?.toPlainString().orEmpty(), s.suggested.toPlainString())
                        }
                    },
                    Modifier.padding(top = 8.dp), style = ButtonStyle.TONAL, size = ButtonSize.COMPACT
                )
            }
        }

        // One limit for every credit card together.
        Column(
            Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Set the credit card limit") { editingCardLimit = true }.padding(top = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle("Credit cards, all together")
                    Caption(
                        if (cardSpend == null) "One monthly limit that applies across all your cards"
                        else "${DisplayFormatters.rupees(cardSpend.used)} of ${DisplayFormatters.rupees(cardSpend.limit)} this month",
                        maxLines = 2
                    )
                }
                AppButton(if (cardSpend == null) "Set" else "Edit", { editingCardLimit = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
            if (cardSpend != null) {
                com.zyke.message.presentation.dashboard.components.ShareBar(
                    cardSpend.fraction,
                    color = when {
                        cardSpend.isOver -> MaterialTheme.colorScheme.error
                        cardSpend.isNear -> com.zyke.message.presentation.dashboard.components.ChartColors.warning
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Caption(
                    if (cardSpend.isOver) "${DisplayFormatters.rupees(cardSpend.over)} over the limit"
                    else "${DisplayFormatters.rupees(cardSpend.remaining)} left across your cards",
                    Modifier.padding(top = 4.dp),
                    color = if (cardSpend.isOver) MaterialTheme.colorScheme.error else com.zyke.message.presentation.messages.components.mutedColor()
                )
            }
        }
        limits.forEach { entry ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .fullWidthClickable(role = Role.Button, onClickLabel = "Edit limits for ${entry.category.displayString}") { editing = entry }
                    .padding(top = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle(entry.category.displayString, Modifier.weight(1f))
                    AppButton("Edit", { editing = entry }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
                entry.daily?.let { LimitStatus(it, Modifier.padding(top = 4.dp)) }
                entry.monthly?.let { LimitStatus(it, Modifier.padding(top = 8.dp)) }
                entry.perDayLeft?.let {
                    Caption(
                        "${DisplayFormatters.rupees(it)} a day keeps you within",
                        Modifier.padding(top = 6.dp),
                        maxLines = 3
                    )
                }
            }
        }
        AppButton(
            text = "Add a limit",
            onClick = { adding = true },
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.padding(top = 14.dp)
        )
    }

    if (editingCardLimit) {
        var text by rememberSaveable { mutableStateOf(cardSpend?.limit?.stripTrailingZeros()?.toPlainString().orEmpty()) }
        FormSheet(
            title = "Credit card limit",
            subtitle = "All your credit cards together, each month",
            onDismiss = { editingCardLimit = false },
            primaryLabel = "Save limit",
            primaryEnabled = text.toBigDecimalOrNull()?.let { it.signum() > 0 } == true,
            hint = "Enter an amount",
            onPrimary = { onCardLimit(text); editingCardLimit = false },
            secondaryLabel = if (cardSpend != null) "Remove limit" else null,
            onSecondary = if (cardSpend != null) ({ onCardLimit(""); editingCardLimit = false }) else null
        ) {
            AmountField(
                "Most to spend on cards a month", text, { text = it },
                supporting = "Use it on any card. What you spend on all of them counts towards it.",
                quickAdds = listOf(5_000, 10_000, 20_000)
            )
        }
    }

    if (adding) {
        LimitDialog(
            category = null,
            daily = null,
            monthly = null,
            onSave = { category, daily, monthly -> onSave(category, daily, monthly); adding = false },
            onRemove = null,
            onDismiss = { adding = false }
        )
    }
    editing?.let { entry ->
        LimitDialog(
            category = entry.category,
            daily = entry.daily?.limit,
            monthly = entry.monthly?.limit,
            onSave = { category, daily, monthly -> onSave(category, daily, monthly); editing = null },
            onRemove = { onRemove(entry.category); editing = null },
            onDismiss = { editing = null }
        )
    }
}

/** Pick a category (when adding), then a monthly limit, a daily limit, or both. A blank field means no limit. */
@Composable
private fun LimitDialog(
    category: MessageCategory?,
    daily: BigDecimal?,
    monthly: BigDecimal?,
    onSave: (MessageCategory, String, String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var chosen by rememberSaveable { mutableStateOf(category) }
    var dailyText by rememberSaveable { mutableStateOf(daily?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var monthlyText by rememberSaveable { mutableStateOf(monthly?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    val hasAmount = dailyText.isNotBlank() || monthlyText.isNotBlank()
    val categories = MessageCategory.assignable

    FormSheet(
        title = category?.let { "Limits · ${it.displayString}" } ?: "Add a spending limit",
        onDismiss = onDismiss,
        primaryLabel = "Save limit",
        primaryEnabled = chosen != null && hasAmount,
        onPrimary = { chosen?.let { onSave(it, dailyText, monthlyText) } },
        secondaryLabel = if (onRemove != null) "Remove limits" else null,
        onSecondary = onRemove
    ) {
        if (category == null) {
            PickField(
                "What is it for?", categories.map { it.displayString }, chosen?.displayString,
                onPick = { name -> chosen = categories.firstOrNull { it.displayString == name } },
                placeholder = "Choose a category"
            )
        }
        AmountField(
            "Each month", monthlyText, { monthlyText = it },
            supporting = "Leave blank for no monthly limit", quickAdds = listOf(500, 1000, 5000)
        )
        AmountField(
            "Each day", dailyText, { dailyText = it },
            supporting = "Leave blank for no daily limit. You'll see green while there's room and red once you're over.",
            quickAdds = listOf(100, 500)
        )
    }
}
