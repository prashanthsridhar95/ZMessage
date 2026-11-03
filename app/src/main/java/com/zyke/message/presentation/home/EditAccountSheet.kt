package com.zyke.message.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import java.math.BigDecimal

private const val MAX_TEXT = 30
private const val MAX_AMOUNT = 14

/**
 * Edit an account: the person's own name for it, its bank (the known banks are suggested as they type; any other name
 * is accepted as typed) and its type. "Reset to detected" goes back to what the messages say about the bank and type.
 */
@Composable
internal fun EditAccountSheet(
    account: AccountSummary,
    onSave: (name: String, bank: String, kind: AccountKind?, balance: BigDecimal?) -> Unit,
    onDismiss: () -> Unit
) {
    val key = account.key
    var name by rememberSaveable(key) { mutableStateOf(account.userCard?.name.orEmpty()) }
    var bank by rememberSaveable(key) { mutableStateOf(account.bankLabel) }
    var kind by rememberSaveable(key) { mutableStateOf(account.kind) }
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val shownBalance = account.balance
    val shownAdjustment = account.balanceAdjustment
    // What the person typed before is what they see again (the balance now is that plus the payments since).
    val typedBefore = if (account.balanceByYou && shownBalance != null) (shownBalance - (shownAdjustment ?: BigDecimal.ZERO)).stripTrailingZeros().toPlainString() else ""
    var balance by rememberSaveable(key) { mutableStateOf(typedBefore) }
    val balanceValue = balance.replace(",", "").trim().toBigDecimalOrNull()
    val balanceBad = balance.isNotBlank() && balanceValue == null
    val kinds = AccountKind.entries
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val detected = account.detectedBank.displayString
    val changed = account.override != null || account.bankLabel != detected || account.kind != account.detectedKind
    FormSheet(
        title = "Edit account",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = bank.isNotBlank() && !balanceBad,
        hint = "Pick or type a bank",
        asPage = true,
        secondaryLabel = if (changed) "Reset to detected" else null,
        onSecondary = if (changed) ({ onSave(name, "", null, null) }) else null,
        onPrimary = { onSave(name, bank, kind.takeIf { it != account.detectedKind }, balanceValue.takeIf { kind == AccountKind.BANK_ACCOUNT }) }
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
        if (kind == AccountKind.BANK_ACCOUNT) {
            FormField(
                "Current balance", balance, { balance = it.take(MAX_AMOUNT) }, placeholder = "What the account holds now",
                prefix = "\u20B9", keyboardType = KeyboardType.Decimal,
                error = if (balanceBad) "Type a number" else null,
                supporting = "As of now. Payments after this adjust it, until a bank message states a newer balance. Leave empty to use only bank messages."
            )
        }
    }
}
