package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor

/**
 * A bottom sheet for choosing what a screen shows. Choices apply the moment they are made,
 * and the button at the bottom says what you will see, so there is nothing to "apply" blindly.
 */
@Composable
fun FilterSheet(
    title: String,
    doneLabel: String,
    canReset: Boolean,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                AppButton("Reset", onReset, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = canReset)
            }

            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                content = content
            )

            AppButton(
                text = doneLabel,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
fun SheetSection(title: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = mutedColor()
    )
}

/** One choice in a single-choice list: a radio button, a label and an optional count. */
@Composable
fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        if (trailing != null) Caption(trailing)
    }
}
