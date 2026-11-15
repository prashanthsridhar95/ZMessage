package com.zyke.message.presentation.messages.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.accounts.PaymentInstrument
import com.zyke.message.domain.accounts.PaymentInstruments
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.scaledLines
import com.zyke.message.presentation.theme.Spacing

/** The person's accounts, so a row can say whether ••8065 is a credit card, a debit card or a bank account. Provided once by the app shell. */
internal val LocalPaymentInstruments = compositionLocalOf { PaymentInstruments.Lookup.EMPTY }

/** The instrument a message was paid with: the matched account's kind, else the wording. Worked out once per message and accounts. */
@Composable
internal fun rememberInstrument(item: ClassifiedMessage): PaymentInstrument {
    val lookup = LocalPaymentInstruments.current
    return remember(item, lookup) { PaymentInstruments.of(item, lookup) }
}

/** The small monochrome glyph of an instrument; null for [PaymentInstrument.UNKNOWN]. */
@DrawableRes
internal fun PaymentInstrument.icon(): Int? = when (this) {
    PaymentInstrument.CREDIT_CARD -> R.drawable.ic_instrument_credit_card
    PaymentInstrument.DEBIT_CARD -> R.drawable.ic_instrument_debit_card
    PaymentInstrument.BANK_ACCOUNT -> R.drawable.ic_instrument_bank
    PaymentInstrument.WALLET -> R.drawable.ic_wallet_24
    PaymentInstrument.FASTAG -> R.drawable.ic_instrument_fastag
    PaymentInstrument.UNKNOWN -> null
}

/** "•• 8065 · 10:20 PM": the masked number (when there is one) then the rest, as one caption line. */
internal fun instrumentCaptionText(last4: String?, rest: String): String =
    listOfNotNull(last4?.takeIf { it.isNotEmpty() }?.let { "•• $it" }, rest.takeIf { it.isNotEmpty() }).joinToString(" · ")

/**
 * The caption of a payment: a small icon for what it was paid with, then "•• 8065 · time". One line with an ellipsis; the
 * icon and the text share a row so the icon never wraps alone, and it grows a little with the font size. The icon is
 * decoration: the row's spoken summary names the instrument. Nothing extra is drawn for an unknown instrument.
 */
@Composable
internal fun PaymentInstrumentCaption(
    instrument: PaymentInstrument,
    last4: String?,
    rest: String,
    modifier: Modifier = Modifier,
    color: Color = mutedColor()
) {
    val text = instrumentCaptionText(last4, rest)
    val icon = instrument.icon()
    if (icon == null) {
        Caption(text, modifier, color)
        return
    }
    val scale = LocalDensity.current.fontScale
    val size = (14f * (1f + (scale - 1f).coerceIn(0f, 1f) * 0.5f)).dp
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Image(
            modifier = Modifier.size(size),
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color)
        )
        Text(
            modifier = Modifier.weight(1f, fill = false),
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = scaledLines(1),
            overflow = TextOverflow.Ellipsis,
            color = color
        )
    }
}
