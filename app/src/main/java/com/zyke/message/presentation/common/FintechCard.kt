package com.zyke.message.presentation.common

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.LocalTrueBlack
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.tonalFill

/**
 * The surface everything on the money screens sits on: a soft, rounded panel with room inside. A card with an [onClick]
 * dips a little while pressed (see pressFeedback) and keeps its ripple and button role. [level] says how much the
 * card matters: [Tonal.Quiet] for most, [Tonal.Hero] (a tint of the accent, larger corners) for the one a screen is about.
 */
@Composable
fun FintechCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** A light that travels round the card's edge, for the one card that needs the person now. */
    movingOutline: Boolean = false,
    level: Tonal = Tonal.Quiet,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = if (level == Tonal.Hero) AppShapes.hero else AppShapes.card
    val corner = if (level == Tonal.Hero) Radius.hero else Radius.card
    val trueBlack = LocalTrueBlack.current
    // Rows inside let their touch ripple run out to the card's edges, across its side padding.
    val source = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalContentInset provides Spacing.xl) {
        Column(
            modifier
                .then(if (onClick != null) Modifier.pressFeedback(source) else Modifier)
                .fillMaxWidth()
                .clip(shape)
                .background(tonalFill(level))
                .then(if (trueBlack) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), shape) else Modifier)
                .then(if (movingOutline) Modifier.movingBorder(MaterialTheme.colorScheme.primary, corner = corner) else Modifier)
                .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick) else Modifier)
                .padding(Spacing.xl),
            content = content
        )
    }
}
