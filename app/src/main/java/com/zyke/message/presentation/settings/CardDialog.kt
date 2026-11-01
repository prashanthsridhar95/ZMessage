package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.DayOfMonthField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import com.zyke.message.presentation.common.Text
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.cards.CardTheme
import com.zyke.message.domain.cards.CardThemes
import com.zyke.message.presentation.common.CardFace
import java.util.UUID

private const val LAST_DIGITS = 4
private const val MAX_DAY = 31

/** Add or edit one card or account: a live picture of the card on top, then its type, bank, network, model and colour. Only the last four digits are asked for, never the full number. */
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
    var variant by rememberSaveable { mutableStateOf(initial?.variant.orEmpty()) }
    var network by rememberSaveable { mutableStateOf(initial?.network) }
    var themeId by rememberSaveable { mutableStateOf(initial?.themeId.orEmpty()) }
    var day by rememberSaveable { mutableStateOf(initial?.statementDay?.toString().orEmpty()) }
    var dueDay by rememberSaveable { mutableStateOf(initial?.dueDay?.toString().orEmpty()) }
    var limit by rememberSaveable { mutableStateOf(initial?.creditLimit?.toString().orEmpty()) }

    var confirmingRemove by rememberSaveable { mutableStateOf(false) }
    val statementDay = day.toIntOrNull()?.takeIf { it in 1..MAX_DAY }
    val due = dueDay.toIntOrNull()?.takeIf { it in 1..MAX_DAY }
    val isAccount = type == CardType.ACCOUNT
    val valid = last4.length == LAST_DIGITS && (!isAccount || bank.isNotBlank()) &&
        (day.isEmpty() || statementDay != null) && (dueDay.isEmpty() || due != null)

    val guessed = CardCatalog.guessNetwork(bank, variant)
    val preview = UserCard(
        id = initial?.id ?: "preview", type = type, last4 = last4, name = name.trim(), bankName = bank.trim(),
        network = if (isAccount) null else network ?: guessed, variant = if (isAccount) "" else variant.trim(), themeId = themeId
    )

    val types = CardType.entries
    FormSheet(
        title = if (initial == null) "Add a card or account" else "Edit ${type.label.lowercase()}",
        onDismiss = onDismiss,
        asPage = true,
        primaryLabel = "Save",
        primaryEnabled = valid,
        onPrimary = {
            val credit = type == CardType.CREDIT
            onSave(
                preview.copy(
                    id = initial?.id ?: UUID.randomUUID().toString(),
                    statementDay = if (credit) statementDay else null,
                    dueDay = if (credit) due else null,
                    creditLimit = if (credit) limit.toLongOrNull()?.takeIf { it > 0 } else null,
                    network = if (isAccount) null else network ?: guessed
                )
            )
        },
        secondaryLabel = if (onDelete != null) "Remove" else null,
        onSecondary = if (onDelete != null) ({ confirmingRemove = true }) else null
    ) {
        CardFace(preview, Modifier.padding(horizontal = 12.dp))
        FormGroup("What is it?") {
            Chips(types.map { ChipOption(it.label, type == it) }, onSelect = { type = types[it] })
        }
        FormField(
            label = if (isAccount) "Bank" else "Bank (optional)",
            value = bank,
            onChange = { bank = it.take(MAX_NAME) },
            placeholder = "Search, or type any other bank",
            suggestions = CardCatalog.banks,
            capitalization = KeyboardCapitalization.Words
        )
        if (!isAccount) {
            FormGroup("Network") {
                val networks = CardNetwork.entries
                Chips(
                    listOf(ChipOption("Not sure", network == null)) + networks.map { ChipOption(it.label, network == it) },
                    onSelect = { network = if (it == 0) null else networks[it - 1] }
                )
            }
            FormField(
                label = "Card name or model (optional)",
                value = variant,
                onChange = { variant = it.take(MAX_NAME) },
                placeholder = "e.g. Regalia, Millennia, Amazon Pay ICICI",
                suggestions = CardCatalog.variantSuggestions(bank, ""),
                capitalization = KeyboardCapitalization.Words
            )
        }
        FormField(
            label = if (isAccount) "Last 4 digits of the account number" else "Last 4 digits of the card",
            value = last4,
            onChange = { input -> last4 = input.filter(Char::isDigit).take(LAST_DIGITS) },
            placeholder = "1234",
            supporting = "Only the last four. Never the full number.",
            keyboardType = KeyboardType.NumberPassword,
            large = true
        )
        FormField(
            label = "Your name for it (optional)",
            value = name,
            onChange = { name = it.take(MAX_NAME) },
            placeholder = if (isAccount) "e.g. Salary account" else "e.g. Rahul"
        )
        FormGroup("Colour") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Swatch("Automatic, from the bank", CardThemes.auto(bank.ifBlank { name }), themeId.isBlank(), auto = true) { themeId = "" }
                CardThemes.all.forEach { t -> Swatch(t.label, t, themeId == t.id, auto = false) { themeId = t.id } }
            }
        }
        if (type == CardType.CREDIT) {
            DayOfMonthField(
                label = "Statement day (optional)",
                value = day,
                onChange = { day = it },
                supporting = "The day of the month the bill is made. Leave it unset for calendar months."
            )
            DayOfMonthField(label = "Payment due day (optional)", value = dueDay, onChange = { dueDay = it })
            FormField(
                label = "Credit limit (optional)",
                value = limit,
                onChange = { input -> limit = input.filter(Char::isDigit).take(MAX_LIMIT_DIGITS) },
                placeholder = "e.g. 200000",
                prefix = "\u20B9",
                keyboardType = KeyboardType.Number
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


private const val MAX_LIMIT_DIGITS = 9

/** One colour to choose: a round gradient, ticked when chosen, with its name for a screen reader. */
@Composable
private fun Swatch(label: String, theme: CardTheme, chosen: Boolean, auto: Boolean, onPick: () -> Unit) {
    Box(
        Modifier.size(48.dp).selectable(selected = chosen, role = Role.RadioButton, onClick = onPick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(theme.colors.map { Color(it) }))
                .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (chosen) Text("\u2713", Modifier.clearAndSetSemantics { }, color = Color.White, fontWeight = FontWeight.Bold)
            else if (auto) Text("A", Modifier.clearAndSetSemantics { }, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
