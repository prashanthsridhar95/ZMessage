package com.zyke.message.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet

private const val MAX_TEXT = 30

/**
 * Edit an account: the person's own name for it, its bank (the known banks are suggested as they type; any other name
 * is accepted as typed) and its type. "Reset to detected" goes back to what the messages say about the bank and type.
 */
@Composable
internal fun EditAccountSheet(
    account: AccountSummary,
    onSave: (name: String, bank: String, kind: AccountKind?) -> Unit,
    onDismiss: () -> Unit
) {
    val key = account.key
    var name by rememberSaveable(key) { mutableStateOf(account.userCard?.name.orEmpty()) }
    var bank by rememberSaveable(key) { mutableStateOf(account.bankLabel) }
    var kind by rememberSaveable(key) { mutableStateOf(account.kind) }
    val kinds = AccountKind.entries
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val detected = account.detectedBank.displayString
    val changed = account.override != null || account.bankLabel != detected || account.kind != account.detectedKind
    FormSheet(
        title = "Edit account",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = bank.isNotBlank(),
        hint = "Pick or type a bank",
        asPage = true,
        secondaryLabel = if (changed) "Reset to detected" else null,
        onSecondary = if (changed) ({ onSave(name, "", null) }) else null,
        onPrimary = { onSave(name, bank, kind.takeIf { it != account.detectedKind }) }
    ) {
        FormField(
            "Name", name, { name = it.take(MAX_TEXT) }, placeholder = "e.g. Salary account",
            capitalization = KeyboardCapitalization.Words
        )
        FormField(
            label = "Bank",
            value = bank,
            onChange = { bank = it.take(MAX_TEXT) },
            placeholder = "Search, or type any other bank",
            supporting = if (detected.isNotEmpty()) "Your messages say $detected. Choose another if that is wrong." else null,
            suggestions = CardCatalog.banks,
            capitalization = KeyboardCapitalization.Words
        )
        FormGroup("Type") {
            Chips(kinds.map { ChipOption(it.label, kind == it) }, onSelect = { kind = kinds[it] })
        }
    }
}
