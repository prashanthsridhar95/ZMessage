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
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
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
    val friends = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    // Equal parts, the person's own part included.
    val share = if (friends.isEmpty()) BigDecimal.ZERO else total.divide(BigDecimal(friends.size + 1), 2, RoundingMode.HALF_UP)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split ${DisplayFormatters.rupees(total)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("For") })
                OutlinedTextField(
                    value = names,
                    onValueChange = { names = it },
                    label = { Text("Who owes you? Names, separated by commas") },
                    minLines = 2
                )
                Text(
                    if (friends.isEmpty()) {
                        "Everyone, you included, pays an equal part."
                    } else {
                        "${DisplayFormatters.rupees(share)} each. When a credit with their name for about that amount arrives, " +
                            "their share is ticked off."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        confirmButton = {
            AppButton(
                "Save",
                {
                    onSave(
                        Split(
                            UUID.randomUUID().toString(), message.id, title.trim().ifEmpty { defaultTitle }, total,
                            friends.map { SplitPerson(it, share) }
                        )
                    )
                },
                style = ButtonStyle.TEXT,
                size = ButtonSize.COMPACT,
                enabled = friends.isNotEmpty()
            )
        },
        dismissButton = { AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
    )
}
