package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.feed.Feed
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.FilterSheet
import com.zyke.message.presentation.common.OptionRow
import com.zyke.message.presentation.common.SheetSection

/** Says what the list is narrowed to and offers a one-tap way back, so a filter is never forgotten. */
@Composable
fun ActiveFilterBar(filter: MessageFilter, resultCount: Int, onChange: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val summary = listOfNotNull(
        filter.smart.takeIf { it != SmartFilter.ALL }?.label,
        filter.category?.displayString
    ).joinToString(" · ")

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "$summary · $resultCount",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onChange) { Text("Change") }
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
fun MessagesFilterSheet(
    filter: MessageFilter,
    feed: Feed,
    onSelectType: (SmartFilter) -> Unit,
    onSelectCategory: (MessageCategory?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    FilterSheet(
        title = "Filter messages",
        doneLabel = "Show ${feed.visibleCount} ${if (feed.visibleCount == 1) "message" else "messages"}",
        canReset = filter.isActive,
        onReset = onReset,
        onDismiss = onDismiss
    ) {
        SheetSection("Type")
        feed.availableFilters.forEach { type ->
            OptionRow(
                label = type.label,
                selected = filter.smart == type,
                trailing = feed.counts[type]?.toString(),
                onClick = { onSelectType(type) }
            )
        }

        SheetSection("Category")
        OptionRow("All categories", selected = filter.category == null, onClick = { onSelectCategory(null) })
        feed.categories.forEach { (category, count) ->
            OptionRow(
                label = category.displayString,
                selected = filter.category == category,
                trailing = count.toString(),
                onClick = { onSelectCategory(category) }
            )
        }
    }
}
