package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.split.Split
import com.zyke.message.domain.split.SplitPerson
import com.zyke.message.domain.split.SplitPayer
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.format.DisplayFormatters
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Split a payment equally between the person and the friends they name. The app does not ask anyone for money: it
 * keeps track of who owes what, and ticks a share off when a matching credit from that name arrives.
 */
@Composable
internal fun SplitDialog(message: ClassifiedMessage, onSave: (Split) -> Unit, onDismiss: () -> Unit) {
    val transaction = message.content as? MessageContent.Transaction ?: return
    val total = transaction.amount.toBigDecimalOrNull() ?: return
    val defaultTitle = message.merchant.name.ifEmpty { transaction.payee }.ifEmpty { "Payment" }

    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var names by rememberSaveable { mutableStateOf("") }
    var friendPaid by rememberSaveable { mutableStateOf(false) }
    val friends = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    // Equal parts, the person's own part included.
    val share = if (friends.isEmpty()) BigDecimal.ZERO else total.divide(BigDecimal(friends.size + 1), 2, RoundingMode.HALF_UP)

    FormSheet(
        title = "Split ${DisplayFormatters.rupees(total)}",
        onDismiss = onDismiss,
        primaryLabel = if (friends.isEmpty()) "Split" else "Split with ${DisplayFormatters.count(friends.size, "friend", "friends")}",
        primaryEnabled = friends.isNotEmpty(),
        hint = "Add at least one name",
        onPrimary = {
            onSave(
                Split(
                    UUID.randomUUID().toString(), message.id, title.trim().ifEmpty { defaultTitle }, total,
                    friends.map { SplitPerson(it, share) },
                    if (friendPaid) SplitPayer.FRIEND else SplitPayer.ME
                )
            )
        }
    ) {
        FormField("For", title, { title = it }, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
        Text("Who paid?", style = MaterialTheme.typography.labelLarge)
        Chips(
            listOf(ChipOption("Me", !friendPaid), ChipOption("Friend", friendPaid)),
            { friendPaid = it == 1 }
        )
        FormField(
            if (friendPaid) "Who paid?" else "Who owes you?", names, { names = it }, placeholder = "Ravi, Priya",
            supporting = if (friends.isEmpty()) {
                if (friendPaid) "The friend who paid. You owe them your equal part." else "Names, separated by commas. Everyone, you included, pays an equal part."
            } else {
                if (friendPaid) {
                    "You owe ${DisplayFormatters.rupees(share)}. Mark it settled from Friends when you have paid."
                } else {
                    "${DisplayFormatters.rupees(share)} each. When a credit with their name for about that amount arrives, their share is ticked off."
                }
            },
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words
        )
    }
}
