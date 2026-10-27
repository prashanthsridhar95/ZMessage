package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.UserCard
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import java.util.UUID

private const val LAST_DIGITS = 4
private const val MAX_DAY = 31

/** Add or edit one card or account: only the last four digits are asked for, never the full number. */
@Composable
internal fun CardDialog(
    initial: UserCard?,
    onSave: (UserCard) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var type by rememberSaveable { mutableStateOf(initial?.type ?: CardType.CREDIT) }
    var last4 by rememberSaveable { mutableStateOf(initial?.last4.orEmpty()) }
    var bank by rememberSaveable { mutableStateOf(initial?.bankName.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var day by rememberSaveable { mutableStateOf(initial?.statementDay?.toString().orEmpty()) }

    var confirmingRemove by rememberSaveable { mutableStateOf(false) }
    val statementDay = day.toIntOrNull()?.takeIf { it in 1..MAX_DAY }
    val valid = last4.length == LAST_DIGITS && (type != CardType.ACCOUNT || bank.isNotBlank()) && (day.isEmpty() || statementDay != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add card or account" else "Edit ${type.label.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardType.entries.forEach {
                        FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.label) })
                    }
                }
                OutlinedTextField(
                    value = last4,
                    onValueChange = { input -> last4 = input.filter(Char::isDigit).take(LAST_DIGITS) },
                    singleLine = true,
                    label = { Text(if (type == CardType.ACCOUNT) "Last 4 digits of account number" else "Last 4 digits of card") },
                    isError = last4.isNotEmpty() && last4.length < LAST_DIGITS,
                    supportingText = { if (last4.length < LAST_DIGITS) Text("Exactly 4 digits. Never enter the full number.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = bank,
                    onValueChange = { bank = it.take(MAX_NAME) },
                    singleLine = true,
                    label = { Text(if (type == CardType.ACCOUNT) "Bank name (required)" else "Bank name (optional)") },
                    placeholder = { Text("e.g. HDFC Bank") }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    singleLine = true,
                    label = { Text("Name (optional)") },
                    placeholder = { Text(if (type == CardType.ACCOUNT) "e.g. Salary account" else "e.g. HDFC Regalia") }
                )
                if (type == CardType.CREDIT) {
                    OutlinedTextField(
                        value = day,
                        onValueChange = { input -> day = input.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        isError = day.isNotEmpty() && statementDay == null,
                        label = { Text("Statement date (day of month)") },
                        supportingText = { Text("The cycle runs from the day after it to the next one. Leave blank for calendar months.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            AppButton(
                "Save",
                {
                    onSave(
                        UserCard(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            type = type,
                            last4 = last4,
                            name = name.trim(),
                            bankName = bank.trim(),
                            statementDay = if (type == CardType.CREDIT) statementDay else null
                        )
                    )
                },
                style = ButtonStyle.TEXT,
                size = ButtonSize.COMPACT,
                enabled = valid
            )
        },
        dismissButton = {
            if (onDelete != null) {
                AppButton("Remove", { confirmingRemove = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            } else {
                AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    )

    if (confirmingRemove && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove this card or account?") },
            text = { Text("Its messages stay in your inbox. They just stop being grouped under it.") },
            confirmButton = { AppButton("Remove", onDelete, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Keep", { confirmingRemove = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

private const val MAX_NAME = 30
