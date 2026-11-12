package com.zyke.message.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Whether money is hidden right now, and the eye button's action. [hidden] is what to draw (the setting is on and no
 * reveal window is open); [enabled] is the setting itself. Provided once at the root.
 */
class PrivacyState(val hidden: Boolean, val toggle: () -> Unit, val enabled: Boolean = hidden)

val LocalPrivacyMode = compositionLocalOf { PrivacyState(hidden = false, toggle = {}) }

const val HIDDEN_AMOUNT = "₹ ••••"

/**
 * A money figure that, while amounts are hidden, shows dots; tapping it asks for the phone's screen lock (or, on a phone
 * without one, just reveals) and then shows every amount for a short while. Screen readers hear "Amount hidden".
 * With amounts visible it is a plain [Text].
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
    overflow: TextOverflow = TextOverflow.Clip,
    /** One line that shrinks (down to about half the size) to fit the room, for the big figures; see [FitText]. */
    fit: Boolean = false,
    /** With [fit]: size the text for this one (the final figure of a count) instead of [text]. */
    sizeAgainst: String? = null
) {
    val privacy = LocalPrivacyMode.current
    if (privacy.hidden) {
        val reveal = LocalAmountReveal.current
        Text(
            HIDDEN_AMOUNT,
            modifier.semantics { contentDescription = "Amount hidden. Double tap to unlock" }
                .clickable(role = Role.Button, onClickLabel = "Unlock amounts") { reveal.requestReveal() },
            color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = 1
        )
    } else {
        if (fit && maxLines == 1) FitText(text, modifier, style = style, color = color, fontWeight = fontWeight, textAlign = textAlign, sizeAgainst = sizeAgainst)
        else Text(text, modifier, color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines, overflow = overflow)
    }
}
