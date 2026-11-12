package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.IconSize
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole

/** The round icon every empty, error and gate page starts with: one size, one tint, so they read as one family. */
@Composable
fun StateIcon(icon: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(IconSize.disc).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(IconSize.feature),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * The one empty page: a round icon, a friendly line saying what this place is for, one line of why it is empty and, when
 * there is something to do about it, one button. Every screen that can be empty says so in this voice (docs/DESIGN.md,
 * Empty, error and loading voice): sentence case, no full stop on the title, no blame, one action at most.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    FintechCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) StateIcon(icon)
            Text(
                title, Modifier.padding(top = if (icon != null) Spacing.lg else 0.dp).heading(), style = TypeRole.title,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center
            )
            Caption(body, Modifier.padding(top = Spacing.xs, bottom = if (actionLabel != null) Spacing.lg else 0.dp), maxLines = 3, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) AppButton(actionLabel, onAction)
        }
    }
}
