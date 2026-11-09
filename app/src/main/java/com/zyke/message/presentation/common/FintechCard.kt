package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** The surface everything on the money screens sits on: a soft, rounded panel with room inside. */
@Composable
fun FintechCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** A light that travels round the card's edge, for the one card that needs the person now. */
    movingOutline: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val trueBlack = com.zyke.message.presentation.theme.LocalTrueBlack.current
    // Rows inside let their touch ripple run out to the card's edges, across its side padding.
    val source = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalContentInset provides 20.dp) {
        Column(
            modifier
                .then(if (onClick != null) Modifier.pressScale(source, Motion.CARD_PRESSED_SCALE) else Modifier)
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (trueBlack) 0.09f else 0.05f))
                .then(if (trueBlack) Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), shape) else Modifier)
                .then(if (movingOutline) Modifier.movingBorder(MaterialTheme.colorScheme.primary, corner = 24.dp) else Modifier)
                .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
                .padding(20.dp),
            content = content
        )
    }
}

