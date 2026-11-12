package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole

/**
 * The one header of a card: a small spaced label (shown in capitals) and, at the end, an optional action or figure. Every
 * card on a screen starts with this, so the eye finds the same thing in the same place. The label is a heading for
 * screen readers and stays on one line; [trailing] keeps its size.
 */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().padding(bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            Modifier.weight(1f).heading(),
            style = TypeRole.section,
            color = mutedColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}
