package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.zyke.message.R
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole

/**
 * What went wrong, in plain words, and a way to try again: errors are never a dead end. Same tile and rhythm as the
 * empty state; it appears at once (a crossfade at most) and is announced politely to a screen reader.
 */
@Composable
fun ErrorState(
    title: String,
    hint: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int = R.drawable.ic_info_24,
    retryLabel: String = "Try again"
) {
    Column(
        modifier.fillMaxSize().padding(Spacing.xxxl).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StateIcon(icon)
        Text(title, Modifier.heading(), style = TypeRole.title, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Caption(hint, maxLines = 3, textAlign = TextAlign.Center)
        AppButton(retryLabel, onRetry)
    }
}
