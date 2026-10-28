package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.forecast.LimitSuggestion
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal

/**
 * Monthly limits worked out from what was spent in each category over the recent cycles: a little under the usual, so
 * a limit asks for a small improvement. Each can be set in one tap, or all of them together.
 */
@Composable
internal fun LimitSuggestionsCard(
    suggestions: List<LimitSuggestion>,
    onSet: (MessageCategory, BigDecimal) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard("Suggested limits", modifier, subtitle = "From what you usually spend", startOpen = true) {
        suggestions.forEachIndexed { index, s ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle(s.category.displayString)
                    Caption(
                        "Usually ${DisplayFormatters.rupees(s.usual)} a month" +
                            (s.current?.let { " · limit now ${DisplayFormatters.rupees(it)}" } ?: ""),
                        maxLines = 2
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        DisplayFormatters.rupees(s.suggested),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    AppButton(if (s.current == null) "Set limit" else "Use this", { onSet(s.category, s.suggested) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
            }
        }
        AppButton(
            "Set all",
            { suggestions.forEach { onSet(it.category, it.suggested) } },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            style = ButtonStyle.TONAL
        )
    }
}
