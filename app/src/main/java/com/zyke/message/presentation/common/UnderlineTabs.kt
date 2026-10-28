package com.zyke.message.presentation.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.zyke.message.presentation.messages.components.mutedColor

/**
 * Quick filters as plain text tabs with a sliding underline: no boxes, no borders.
 * The selected tab is bold and underlined; counts sit beside the label in a quieter tone.
 */
@Composable
fun UnderlineTabs(
    labels: List<String>,
    counts: List<Int?>,
    /** Optional drawable per tab, shown before the label and tinted with the tab's colour. */
    icons: List<Int?> = emptyList(),
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Off when the tabs share a row with something else and a full-width line is drawn underneath instead. */
    showDivider: Boolean = true
) {
    if (labels.isEmpty()) return
    ScrollableTabRow(
        selectedTabIndex = selectedIndex.coerceIn(0, labels.lastIndex),
        modifier = modifier,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        edgePadding = 8.dp,
        divider = { if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) }
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = mutedColor(),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        icons.getOrNull(index)?.let { icon ->
                            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                        counts.getOrNull(index)?.let { count ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary else mutedColor()
                            )
                        }
                    }
                }
            )
        }
    }
}
