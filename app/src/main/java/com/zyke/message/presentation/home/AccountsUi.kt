package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.theme.TypeRole
import java.util.Locale

/*
 * What is shared by the account and card headers: the arithmetic line under a bank balance and the overflow menu of the
 * front card or account. The tiles themselves are drawn by the deck (AccountsHeader.kt) and the sub-tab pages.
 */

private val WHY_DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * The arithmetic under a bank balance: where it started and what has moved since, so a total that looks wrong can be
 * checked. Null when there is no balance to explain or nothing has moved. Amounts in it are hidden with the others.
 */
internal fun balanceWhy(account: AccountSummary): String? {
    // Local copies: these come from another module, so they cannot be smart-cast after a null check.
    val asOf = account.balanceAsOf ?: return null
    if (account.balance == null || account.isCard) return null
    val adjustment = account.balanceAdjustment
    val day = WHY_DAY.format(asOf)
    val source = if (account.balanceByYou) "Balance set by you on $day" else "From the $day message"
    val parts = ArrayList<String>()
    when {
        adjustment != null -> {
            val sign = if (adjustment.signum() < 0) "\u2212" else "+"
            parts += "$sign${DisplayFormatters.rupees(adjustment.abs())} across ${DisplayFormatters.count(account.movementCount, "payment", "payments")} since"
        }
        account.adjustmentRefused ->
            parts += "${DisplayFormatters.count(account.movementCount, "payment", "payments")} since would take it below zero, so ${if (account.movementCount == 1) "it is" else "they are"} not applied"
    }
    if (account.paymentsNotCounted > 0) parts += "${DisplayFormatters.count(account.paymentsNotCounted, "payment", "payments")} not counted"
    return if (parts.isEmpty()) null else "$source, ${parts.joinToString(", ")}"
}

/**
 * The one overflow button at the end of a tile's top line (48dp). Details is also the tile's own tap, but it stays in the
 * menu so a screen-reader user finds all three in one place; a card adds "Edit card", which opens the cards form in Settings.
 */
@Composable
internal fun AccountActions(
    name: String,
    isCard: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLeaveOut: () -> Unit,
    onCheckBalance: (() -> Unit)? = null,
    onBalanceSettings: (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val openSettings = LocalOpenSettings.current
    Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.baseline_more_horiz_24), contentDescription = "Actions for $name")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionItem("Details", null, R.drawable.ic_info_24) { open = false; onOpen() }
            if (onCheckBalance != null) ActionItem("Check balance by SMS", "Text your bank for the latest balance", R.drawable.baseline_message_24) { open = false; onCheckBalance() }
            if (onBalanceSettings != null) ActionItem("Balance check settings", "Number, words, ask before sending", R.drawable.ic_info_24) { open = false; onBalanceSettings() }
            ActionItem("Edit", "Name, bank and type", R.drawable.ic_edit_24) { open = false; onRename() }
            if (isCard) ActionItem("Edit card", "Limit, bill dates and look", R.drawable.ic_card_24) { open = false; openSettings() }
            ActionItem("Leave out of totals", "Hidden from totals and lists", R.drawable.ic_visibility_off_24) { open = false; onLeaveOut() }
        }
    }
}

@Composable
private fun ActionItem(label: String, hint: String?, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = TypeRole.body)
                if (hint != null) Text(hint, style = TypeRole.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

