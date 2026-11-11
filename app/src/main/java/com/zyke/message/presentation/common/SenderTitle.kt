package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.conversation.SenderDisplay
import com.zyke.message.domain.conversation.SenderLabel

/** The small filled check beside a registered sender's name. Described for screen readers; drawn from the theme's primary colour. */
@Composable
fun VerifiedBadge(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Image(
        painter = painterResource(R.drawable.ic_verified_24),
        contentDescription = "Verified sender",
        modifier = modifier.size(16.dp),
        colorFilter = ColorFilter.tint(tint)
    )
}

/**
 * A sender's name, with the verified check next to it when the header was a registered one (see [SenderDisplay]). The name takes
 * one line and is cut with an ellipsis, so the badge is never pushed off or wrapped onto a line of its own. A screen reader hears
 * "Spinny, verified sender" as one item.
 */
@Composable
fun SenderTitle(
    label: SenderLabel,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label.spoken },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.name,
            modifier = Modifier.weight(1f, fill = false),
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = color
        )
        if (label.verified) {
            Spacer(Modifier.width(4.dp))
            VerifiedBadge()
        }
    }
}

/** The label for a message's sender: the person's name for it (if any) wins, the badge follows the header. */
@Composable
fun rememberSenderLabel(sender: String, nickname: String? = null, fallbackName: String? = null): SenderLabel =
    remember(sender, nickname, fallbackName) { SenderDisplay.of(sender, nickname, fallbackName) }
