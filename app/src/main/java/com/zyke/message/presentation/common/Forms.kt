package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption

/**
 * The one shape for entering something: a bottom sheet with a title, labelled fields, one filled button to save and,
 * when it applies, a quiet one to remove. The sheet moves up with the keyboard so the field being typed in is
 * never hidden.
 */
@Composable
fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(title, Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            content()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppButton(primaryLabel, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
                if (secondaryLabel != null && onSecondary != null) {
                    AppButton(secondaryLabel, onSecondary, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT)
                }
            }
        }
    }
}

/** A field with its label above it, so the label stays readable once something is typed. */
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    prefix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    large: Boolean = false
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = if (large) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
            placeholder = placeholder?.let { { Text(it) } },
            prefix = prefix?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        )
        if (supporting != null) Caption(supporting, maxLines = 2)
    }
}

/** An amount in rupees, large, with optional one-tap amounts that are added to what is there. */
@Composable
fun AmountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    quickAdds: List<Int> = emptyList()
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormField(
            label, value, { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
            prefix = "₹", keyboardType = KeyboardType.Decimal, supporting = supporting, large = true
        )
        if (quickAdds.isNotEmpty()) {
            Chips(
                quickAdds.map { amount -> ChipOption("+${com.zyke.message.presentation.format.DisplayFormatters.rupees(java.math.BigDecimal(amount))}", false) },
                onSelect = { index -> onChange(((value.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) + java.math.BigDecimal(quickAdds[index])).stripTrailingZeros().toPlainString()) }
            )
        }
    }
}

data class ChipOption(val label: String, val selected: Boolean)

/** A wrapping row of choices; one tap picks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<ChipOption>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, option ->
            FilterChip(selected = option.selected, onClick = { onSelect(index) }, label = { Text(option.label) })
        }
    }
}

/** A label above a group of choices. */
@Composable
fun FormGroup(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
        content()
    }
}

@Composable
fun FormSpacer() = Spacer(Modifier.height(4.dp))
