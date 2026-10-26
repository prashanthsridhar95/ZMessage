package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.zyke.message.domain.model.MessageCategory
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly budget · ${category.displayString}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' } },
                singleLine = true,
                prefix = { Text("₹") },
                label = { Text("Limit per month") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { onSave("") }) { Text("Remove") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
