package com.zyke.message.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

/** Whether the big money figures are hidden, and a way to flip it. Provided once at the root from the settings. */
class PrivacyState(val hidden: Boolean, val toggle: () -> Unit)

val LocalPrivacyMode = compositionLocalOf { PrivacyState(hidden = false, toggle = {}) }

const val HIDDEN_AMOUNT = "₹ ••••"
private const val REVEAL_MILLIS = 5_000L

/**
 * A money figure that, in privacy mode, shows dots until tapped; a tap shows it for five seconds, then it hides again.
 * Screen readers hear "Amount hidden" meanwhile. With privacy mode off it is a plain [Text].
 */
@Composable
fun PrivateAmount(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val privacy = LocalPrivacyMode.current
    var revealed by remember(privacy.hidden) { mutableStateOf(false) }
    LaunchedEffect(revealed) { if (revealed) { delay(REVEAL_MILLIS); revealed = false } }
    if (privacy.hidden && !revealed) {
        Text(
            HIDDEN_AMOUNT,
            modifier.semantics { contentDescription = "Amount hidden" }
                .clickable(role = Role.Button, onClickLabel = "Show amount for 5 seconds") { revealed = true },
            color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = 1
        )
    } else {
        Text(text, modifier, color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines, overflow = overflow)
    }
}
