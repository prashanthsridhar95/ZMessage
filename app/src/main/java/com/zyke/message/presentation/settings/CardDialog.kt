package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.DayOfMonthField
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
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.ChipOption
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

    val types = CardType.entries
    FormSheet(
        title = if (initial == null) "Add a card or account" else "Edit ${type.label.lowercase()}",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = valid,
        onPrimary = {
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
        secondaryLabel = if (onDelete != null) "Remove" else null,
        onSecondary = if (onDelete != null) ({ confirmingRemove = true }) else null
    ) {
        FormGroup("What is it?") {
            Chips(types.map { ChipOption(it.label, type == it) }, onSelect = { type = types[it] })
        }
        FormField(
            label = if (type == CardType.ACCOUNT) "Last 4 digits of the account number" else "Last 4 digits of the card",
            value = last4,
            onChange = { input -> last4 = input.filter(Char::isDigit).take(LAST_DIGITS) },
            placeholder = "1234",
            supporting = "Only the last four. Never the full number.",
            keyboardType = KeyboardType.NumberPassword,
            large = true
        )
        FormField(
            label = if (type == CardType.ACCOUNT) "Bank" else "Bank (optional)",
            value = bank,
            onChange = { bank = it.take(MAX_NAME) },
            placeholder = "e.g. HDFC Bank",
            suggestions = BANKS,
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
        )
        FormField(
            label = "Your name for it (optional)",
            value = name,
            onChange = { name = it.take(MAX_NAME) },
            placeholder = if (type == CardType.ACCOUNT) "e.g. Salary account" else "e.g. HDFC Regalia"
        )
        if (type == CardType.CREDIT) {
            DayOfMonthField(
                label = "Statement day (optional)",
                value = day,
                onChange = { day = it },
                supporting = "The day of the month the bill is made. Leave it unset for calendar months."
            )
        }
    }

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

private val BANKS = listOf(
    "HDFC Bank", "ICICI Bank", "State Bank of India", "Axis Bank", "Kotak Mahindra Bank", "IDFC FIRST Bank", "Yes Bank", "IndusInd Bank",
    "RBL Bank", "Federal Bank", "Bank of Baroda", "Punjab National Bank", "Canara Bank", "AU Small Finance Bank", "American Express",
    "Standard Chartered", "HSBC", "Citibank", "Union Bank of India", "Bank of India"
)
