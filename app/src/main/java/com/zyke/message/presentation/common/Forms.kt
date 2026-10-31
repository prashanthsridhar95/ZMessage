package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
 * The one shape for entering something: a bottom sheet with a title and a close button, labelled fields that scroll,
 * and a footer that stays in view with one filled button to save and, when it applies, a quiet one to remove. While
 * the save button is off, [hint] says what is still missing. The sheet moves up with the keyboard so the field being
 * typed in is never hidden.
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
    subtitle: String? = null,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        title, Modifier.heading(), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                    if (subtitle != null) Caption(subtitle, Modifier.padding(top = 2.dp), maxLines = 2)
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u2715", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                content()
            }
            // The footer does not scroll away: the way to save is always where the thumb expects it.
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!primaryEnabled && hint != null) {
                    Caption(hint, Modifier.fillMaxWidth().padding(bottom = 4.dp), maxLines = 2, textAlign = TextAlign.Center)
                }
                AppButton(primaryLabel, onPrimary, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = primaryEnabled)
                if (secondaryLabel != null && onSecondary != null) {
                    val destructive = secondaryLabel.startsWith("Delete", ignoreCase = true) || secondaryLabel.startsWith("Remove", ignoreCase = true)
                    TextButton(
                        onClick = onSecondary,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    ) { Text(secondaryLabel, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** A field with its label above it, so the label stays readable once something is typed; an [error] shows beneath it in red. */
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
    large: Boolean = false,
    error: String? = null,
    /** Offered while the field is being typed in: those that contain what is typed (a few, when nothing is yet). */
    suggestions: List<String> = emptyList(),
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    /** False for free text: the field grows to a few lines instead of staying on one. */
    singleLine: Boolean = true
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        var focused by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = if (large) 68.dp else 56.dp).onFocusChanged { focused = it.isFocused },
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 5,
            isError = error != null,
            textStyle = if (large) MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
            placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)) } },
            prefix = prefix?.let { { Text(it, style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next, capitalization = capitalization),
            shape = RoundedCornerShape(16.dp),
            // Filled and borderless until it is the one being typed in, then ringed in the brand colour.
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = quiet,
                focusedContainerColor = quiet,
                errorContainerColor = quiet,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            supporting != null -> Caption(supporting, maxLines = 2)
        }
        if (focused) {
            val typed = value.trim()
            val shown = if (typed.isEmpty()) suggestions.take(MAX_SUGGESTIONS)
            else suggestions.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }.take(MAX_SUGGESTIONS)
            if (shown.isNotEmpty()) SuggestionRow(shown) { onChange(it) }
        }
    }
}

private const val MAX_SUGGESTIONS = 6

/** Small tappable completions under a field, in one scrolling line so they never push the form down. */
@Composable
fun SuggestionRow(options: List<String>, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            Text(
                option,
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable(role = Role.Button, onClickLabel = "Use $option", onClick = { onPick(option) })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
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

/** A wrapping row of choices; one tap picks. The chosen one is filled and ticked, so it is plain without colour too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<ChipOption>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            val shape = RoundedCornerShape(12.dp)
            val fill = if (option.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            val ink = if (option.selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(fill)
                    .selectable(selected = option.selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (option.selected) Text("\u2713 ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink)
                Text(option.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Medium, color = ink)
            }
        }
    }
}

/** A label above a group of choices. */
@Composable
fun FormGroup(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        content()
    }
}

@Composable
fun FormSpacer() = Spacer(Modifier.height(4.dp))
