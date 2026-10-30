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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption

/**
 * The one empty page: a round icon, what this place is for, why it is empty and, when there is something to do about it,
 * one button. Every screen that can be empty says so in this voice.
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
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Box(
                    Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(30.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Text(
                title, Modifier.padding(top = if (icon != null) 14.dp else 0.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center
            )
            Caption(body, Modifier.padding(top = 4.dp, bottom = if (actionLabel != null) 16.dp else 0.dp), maxLines = 5, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) AppButton(actionLabel, onAction)
        }
    }
}
