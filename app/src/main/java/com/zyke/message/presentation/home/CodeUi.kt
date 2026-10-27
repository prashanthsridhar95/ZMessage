package com.zyke.message.presentation.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.rememberReduceMotion
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.mutedColor
import kotlinx.coroutines.delay

private const val LOW_FRACTION = 0.2f
private const val SECONDS_IN_MINUTE = 60
private const val COPIED_FOR_MS = 1_800L
private const val APPEAR_MS = 520

/**
 * A one-time code as a card of its own: who sent it, each digit on its own tile, a ring that counts down the minutes it
 * has left and a bar that drains beneath, and one button that says "Copied" once it is. The digits arrive one after
 * another. When the time is up the card dims and the button is switched off.
 */
@Composable
internal fun CodeCard(
    message: ClassifiedMessage,
    nowMillis: Long,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val otp = message.content as? MessageContent.Otp ?: return
    val left = (message.message.date + CODE_LIFE_WINDOW_MS - nowMillis).coerceAtLeast(0)
    val fraction = left.toFloat() / CODE_LIFE_WINDOW_MS
    val seconds = (left / 1000).toInt()
    val expired = seconds == 0
    val low = !expired && fraction <= LOW_FRACTION
    val accent = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val sender = otp.senderName.ifEmpty { message.merchant.name }
    val reduceMotion = rememberReduceMotion()

    // Copied for a moment, then back to "Copy code".
    var copiedAt by remember { mutableLongStateOf(0L) }
    val copied = copiedAt != 0L
    LaunchedEffect(copiedAt) {
        if (copiedAt != 0L) {
            delay(COPIED_FOR_MS)
            copiedAt = 0L
        }
    }

    val appear = remember(otp.code) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(otp.code) { if (!reduceMotion) appear.animateTo(1f, tween(APPEAR_MS)) }

    FintechCard(
        modifier.semantics { contentDescription = "Verification code from $sender, ${otp.code.toList().joinToString(" ")}, " + (if (expired) "expired" else "${seconds / SECONDS_IN_MINUTE} minutes ${seconds % SECONDS_IN_MINUTE} seconds left") },
        onClick = if (expired) null else ({ copiedAt = System.currentTimeMillis(); onCopy() })
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantChip(message.merchant.logo, imageKey = message.merchant.imageKey, name = message.merchant.name, description = sender, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Caption("VERIFICATION CODE")
                Text(sender, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            CountdownRing(fraction, seconds, accent, expired)
        }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val digits = otp.code.toList()
            digits.forEachIndexed { index, digit ->
                // Each tile fades up a beat after the one before it.
                val shown = ((appear.value * (digits.size + 2)) - index).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .height(DIGIT_HEIGHT)
                        .graphicsLayer { translationY = (1f - shown) * 12.dp.toPx() }
                        .alpha(shown * if (expired) 0.4f else 1f)
                        .background(accent.copy(alpha = 0.09f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        digit.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        // The time left, draining.
        Box(
            Modifier.fillMaxWidth().padding(top = 14.dp).height(4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(accent, RoundedCornerShape(2.dp)))
        }

        otp.paymentAmount?.let {
            Caption("For a payment of ${DisplayFormatters.rupees(it)}" + (otp.cardNumber?.let { card -> " · card •• $card" } ?: ""), Modifier.padding(top = 10.dp), maxLines = 1)
        }

        AppButton(
            text = when {
                expired -> "Expired"
                copied -> "Copied ✓"
                else -> "Copy code"
            },
            onClick = { copiedAt = System.currentTimeMillis(); onCopy() },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            style = if (copied) ButtonStyle.TONAL else ButtonStyle.PRIMARY,
            enabled = !expired
        )
    }
}

private val DIGIT_HEIGHT = 58.dp

/** A ring that empties as the code runs out, with the time left as minutes and seconds in its middle. */
@Composable
private fun CountdownRing(fraction: Float, seconds: Int, accent: androidx.compose.ui.graphics.Color, expired: Boolean) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = stroke)
            if (!expired) drawArc(accent, -90f, 360f * fraction, false, style = stroke)
        }
        Text(
            if (expired) "–" else "%d:%02d".format(seconds / SECONDS_IN_MINUTE, seconds % SECONDS_IN_MINUTE),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (expired) mutedColor() else accent,
            maxLines = 1
        )
    }
}
