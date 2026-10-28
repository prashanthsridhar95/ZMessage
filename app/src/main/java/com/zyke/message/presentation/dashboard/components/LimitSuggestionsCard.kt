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
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.AppBottomSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    var open by rememberSaveable { mutableStateOf(false) }

    // One tile on the page; the list, and the buttons that set each limit, are in the sheet it opens.
    FintechCard(modifier, onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption("SUGGESTED LIMITS")
                Text(
                    DisplayFormatters.count(suggestions.size, "category", "categories") + " could use a limit",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Caption("Worked out from what you usually spend", Modifier.padding(top = 2.dp), maxLines = 2)
            }
            Text("\u203A", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }

    if (open) {
        AppBottomSheet({ open = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)
            ) {
                Text("Suggested limits", Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Caption("A little under what you usually spend each month, so a limit asks for a small improvement.", Modifier.padding(top = 4.dp, bottom = 8.dp), maxLines = 3)
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
                    { suggestions.forEach { onSet(it.category, it.suggested) }; open = false },
                    Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        }
    }
}
