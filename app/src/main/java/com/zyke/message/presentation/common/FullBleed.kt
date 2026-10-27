package com.zyke.message.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The side padding of the screen or sheet a row sits in; 0 where rows already reach the edges. */
val LocalContentInset = compositionLocalOf { 0.dp }

/**
 * Like `clickable`, but the touch ripple runs the full width of the screen instead of stopping at the page's side
 * padding. The row is measured wider by the inset on both sides, placed back over the padding, and given the
 * padding again inside, so its content stays where it was.
 */
fun Modifier.fullWidthClickable(
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val inset: Dp = LocalContentInset.current
    if (inset == 0.dp) {
        Modifier.clickable(onClickLabel = onClickLabel, role = role, onClick = onClick)
    } else {
        Modifier
            .layout { measurable, constraints ->
                val extra = inset.roundToPx() * 2
                val fixedWidth = constraints.hasBoundedWidth && constraints.minWidth == constraints.maxWidth
                val widened = constraints.copy(
                    minWidth = if (fixedWidth) constraints.maxWidth + extra else constraints.minWidth,
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
                )
                val placeable = measurable.measure(widened)
                val width = (placeable.width - extra).coerceAtLeast(0)
                layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
            }
            .clickable(onClickLabel = onClickLabel, role = role, onClick = onClick)
            .padding(horizontal = inset)
    }
}
