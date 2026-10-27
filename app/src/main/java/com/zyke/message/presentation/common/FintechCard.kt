package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
fun FintechCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    // The card is the tap target, so the rows in it do not stretch past its edge.
    CompositionLocalProvider(LocalContentInset provides 0.dp) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(20.dp),
            content = content
        )
    }
}

