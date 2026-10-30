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
        Caption("Per day, per month, or both")
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
