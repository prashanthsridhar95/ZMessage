package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.fullWidthClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.wishlist.WishItem
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.AmountText
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.util.UUID

/**
 * Things the person wants to buy. When the category they'd spend it in is over its limit, the home screen suggests
 * letting the purchase wait until next month. The app never buys anything.
 */
@Composable
internal fun WishlistSection(
    items: List<WishItem>,
    onSave: (WishItem) -> Unit,
    onRemove: (String) -> Unit
) {
    var editing by remember { mutableStateOf<WishItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    SectionCard("Wishlist") {
        Caption(
            "Things to buy; waits if the category is over",
            maxLines = 3
        )
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .fullWidthClickable(role = Role.Button, onClickLabel = "Edit ${item.name}") { editing = item }
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    RowTitle(item.name)
                    Caption(item.category?.displayString ?: "Any category")
                }
                AmountText(DisplayFormatters.rupees(item.price), androidx.compose.material3.MaterialTheme.colorScheme.secondary)
            }
        }
        AppButton("Add a wish", { adding = true }, style = ButtonStyle.OUTLINE, modifier = Modifier.padding(top = 14.dp))
    }

    if (adding) {
        WishDialog(null, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false })
    }
    editing?.let { item ->
        WishDialog(item, onSave = { onSave(it); editing = null }, onRemove = { onRemove(item.id); editing = null }, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WishDialog(item: WishItem?, onSave: (WishItem) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(item?.name.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(item?.price?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var category by rememberSaveable { mutableStateOf(item?.category) }
    val amount = price.toBigDecimalOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add a wish" else "Edit wish") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("What") })
                OutlinedTextField(
                    value = price,
                    onValueChange = { input -> price = input.filter { it.isDigit() || it == '.' } },
                    singleLine = true,
                    prefix = { Text("₹") },
                    label = { Text("Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text("Would be spent under")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageCategory.assignable.forEach {
                        FilterChip(
                            selected = category == it,
                            onClick = { category = if (category == it) null else it },
                            label = { Text(it.displayString) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            AppButton(
                "Save",
                { onSave(WishItem(item?.id ?: UUID.randomUUID().toString(), name.trim(), amount!!, category)) },
                style = ButtonStyle.TEXT,
                size = ButtonSize.COMPACT,
                enabled = name.isNotBlank() && amount != null && amount.signum() > 0
            )
        },
        dismissButton = {
            if (onRemove != null) {
                AppButton("Remove", onRemove, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            } else {
                AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    )
}
