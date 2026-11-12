package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.AppearVisibility
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.Spacing

/**
 * A long explanation kept out of the way: a quiet "Learn more" line that opens the text under it and closes it again
 * ("Show less"). Captions in Settings stay at a line or two; anything longer goes here. The open text is announced by
 * its position, the control is a 48 dp button, and the opening is the short card-open motion (instant when reduced).
 */
@Composable
fun LearnMore(text: String, modifier: Modifier = Modifier, label: String = "Learn more") {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.heightIn(min = 48.dp).tappable(
                onClick = { haptics.tick(); open = !open },
                role = Role.Button,
                onClickLabel = if (open) "Hide details" else label
            ),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (open) "Show less" else label,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
            )
        }
        AppearVisibility(open) {
            Caption(text, Modifier.padding(bottom = Spacing.sm), maxLines = 12)
        }
    }
}
