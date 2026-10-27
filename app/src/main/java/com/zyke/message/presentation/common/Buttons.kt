package com.zyke.message.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R

/** How loud a button is. Use one [PRIMARY] per screen area; everything else steps down from it. */
enum class ButtonStyle {
    /** The main action: filled with the brand colour. */
    PRIMARY,

    /** A supporting action: soft tinted fill. */
    TONAL,

    /** A neutral alternative: outline only. */
    OUTLINE,

    /** The quietest: just the label. */
    TEXT
}

enum class ButtonSize { REGULAR, COMPACT }

private val RegularShape = RoundedCornerShape(14.dp)
private val CompactShape = RoundedCornerShape(12.dp)

/**
 * The app's one button. Shape, height, label weight and colours are decided here so every tappable
 * action looks and feels the same; callers only choose a [style] and [size].
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    size: ButtonSize = ButtonSize.REGULAR,
    enabled: Boolean = true
) {
    val compact = size == ButtonSize.COMPACT
    val shape = if (compact) CompactShape else RegularShape
    val padding = if (compact) PaddingValues(horizontal = 14.dp, vertical = 6.dp) else PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    val source = remember { MutableInteractionSource() }
    val sized = modifier.heightIn(min = if (compact) 40.dp else 48.dp).pressScale(source)
    val scheme = MaterialTheme.colorScheme

    val label: @Composable () -> Unit = {
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }

    when (style) {
        ButtonStyle.PRIMARY -> Button(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape, contentPadding = padding,
            interactionSource = source
        ) { label() }

        ButtonStyle.TONAL -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            interactionSource = source,
            colors = ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)
        ) { label() }

        ButtonStyle.OUTLINE -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            interactionSource = source,
            border = BorderStroke(1.5.dp, if (enabled) scheme.primary.copy(alpha = 0.6f) else scheme.onSurface.copy(alpha = 0.12f))
        ) { label() }

        ButtonStyle.TEXT -> TextButton(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape, contentPadding = padding,
            interactionSource = source
        ) { label() }
    }
}

/**
 * An inline "go somewhere" action: label plus a chevron, whole row tappable. Reads as a link, so it is
 * never mistaken for a command that changes data.
 */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CompactShape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(2.dp))
        Image(
            modifier = Modifier.size(20.dp),
            painter = painterResource(R.drawable.baseline_chevron_right_24),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            contentDescription = null
        )
    }
}
