package com.zyke.message.presentation.dashboard.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.FormSheet
import java.math.BigDecimal

/** Asks for a monthly limit; saving a blank value removes the budget. */
@Composable
internal fun BudgetDialog(
    category: MessageCategory,
    current: BigDecimal?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(category) { mutableStateOf(current?.stripTrailingZeros()?.toPlainString().orEmpty()) }

    FormSheet(
        title = "Monthly budget",
        subtitle = category.displayString,
        onDismiss = onDismiss,
        primaryLabel = if (current == null) "Set budget" else "Save budget",
        primaryEnabled = text.toBigDecimalOrNull()?.signum()?.let { it > 0 } == true,
        hint = "Enter how much you want to spend a month",
        onPrimary = { onSave(text) },
        secondaryLabel = if (current != null) "Remove budget" else null,
        onSecondary = if (current != null) ({ onSave("") }) else null
    ) {
        AmountField("Limit per month", text, { text = it }, quickAdds = listOf(500, 1_000, 5_000))
    }
}
