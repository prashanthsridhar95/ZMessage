package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount

/** One piece of a sentence: plain words, or a money figure that privacy mode hides. */
internal class Part(val text: String, val money: Boolean = false)

internal fun words(text: String) = Part(text)
internal fun money(text: String) = Part(text, money = true)

/**
 * A sentence with money in it, drawn so that privacy mode hides only the figures (tap one to show it) and a screen
 * reader hears the whole sentence, or "amount hidden" where a figure is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyLine(
    parts: List<Part>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    moneyColor: Color = color,
    moneyWeight: FontWeight = FontWeight.SemiBold
) {
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = parts.joinToString(" ") { if (it.money && hidden) "amount hidden" else it.text.trim() }
    FlowRow(modifier.semantics(mergeDescendants = true) { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEach { part ->
            if (part.money) PrivateAmount(part.text, color = moneyColor, style = style, fontWeight = moneyWeight, maxLines = 1)
            else Text(part.text.trim(), style = style, color = color)
        }
    }
}
