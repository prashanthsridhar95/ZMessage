package com.zyke.message.presentation.settings

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
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LimitStatus
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal

/**
 * Daily and monthly spending limits per category, with where each one stands: available in green, over in red.
 * This is the only place limits are set; the Money tab only shows how they are going and links here.
 */
@Composable
internal fun LimitsSection(
    limits: List<CategoryLimits>,
    onSave: (MessageCategory, daily: String, monthly: String) -> Unit,
    onRemove: (MessageCategory) -> Unit
) {
    var editing by remember { mutableStateOf<CategoryLimits?>(null) }
    var adding by remember { mutableStateOf(false) }

    SectionCard("Spending limits") {
        Caption(
            "Set a limit per day, per month, or both, for any category. You'll see what's available, or how far over " +
                "you are, here and on the Money tab.",
            maxLines = 4
        )
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
                        "About ${DisplayFormatters.rupees(it)} a day for the rest of the month keeps you within the monthly limit.",
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

/** Pick a category (when adding), then a daily limit, a monthly limit, or both. A blank field means no limit. */
@OptIn(ExperimentalLayoutApi::class)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category?.let { "Limits · ${it.displayString}" } ?: "Add a limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (category == null) {
                    Text("Category", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MessageCategory.assignable.forEach {
                            FilterChip(selected = chosen == it, onClick = { chosen = it }, label = { Text(it.displayString) })
                        }
                    }
                }
                AmountField(dailyText, { dailyText = it }, "Per day")
                AmountField(monthlyText, { monthlyText = it }, "Per month")
                Text(
                    "Leave one blank to set only the other. You'll see green while there's room and red once you're over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        confirmButton = {
            AppButton(
                "Save",
                { chosen?.let { onSave(it, dailyText, monthlyText) } },
                style = ButtonStyle.TEXT,
                size = ButtonSize.COMPACT,
                enabled = chosen != null && hasAmount
            )
        },
        dismissButton = {
            if (onRemove != null) {
                AppButton("Remove both", onRemove, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            } else {
                AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    )
}

@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' }) },
        singleLine = true,
        prefix = { Text("₹") },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}
