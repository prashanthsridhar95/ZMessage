package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.feed.Feed
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.feed.StatusFilter
import com.zyke.message.domain.feed.TimeWindow
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.FilterSheet
import com.zyke.message.presentation.common.SheetSection
import com.zyke.message.presentation.common.TileGrid
import com.zyke.message.presentation.common.TileOption
import com.zyke.message.presentation.common.UnderlineTabs

/** The quick filters along the top of the Inbox: one tab per kind of message, with counts. */
@Composable
fun QuickFilterTabs(feed: Feed, selected: SmartFilter, onSelect: (SmartFilter) -> Unit, modifier: Modifier = Modifier) {
    val types = feed.availableFilters
    UnderlineTabs(
        labels = types.map { it.label },
        counts = types.map { feed.counts[it] },
        icons = types.map { it.icon() },
        selectedIndex = types.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(types[it]) },
        modifier = modifier,
        showDivider = false
    )
}

private fun SmartFilter.icon(): Int = when (this) {
    SmartFilter.ALL -> R.drawable.baseline_message_24
    SmartFilter.MONEY -> R.drawable.baseline_wallet_24
    SmartFilter.OTP -> R.drawable.baseline_lock_24
    SmartFilter.BILLS -> R.drawable.baseline_receipt_24
    SmartFilter.TRAVEL -> R.drawable.baseline_flight_24
    SmartFilter.REMINDERS -> R.drawable.baseline_event_24
    SmartFilter.OTHERS -> R.drawable.baseline_more_horiz_24
    SmartFilter.PROMOS -> R.drawable.baseline_sell_24
    SmartFilter.ARCHIVED -> R.drawable.baseline_archive_24
}

/** Says what the sheet's filters are doing to the list and offers a one-tap way back. */
@Composable
fun ActiveFilterBar(
    summary: String,
    resultCount: Int,
    onChange: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset, vertical = 8.dp)
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
        AppButton("Change", onChange, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        AppButton("Clear", onClear, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
}

internal fun MessageFilter.extrasSummary(): String = listOfNotNull(
    category?.displayString,
    status.takeIf { it != StatusFilter.ANY }?.label,
    window.takeIf { it != TimeWindow.ANY }?.label
).joinToString(" · ")

/** The filters that don't fit along the top: category, read status and time window. */
@Composable
fun MessagesFilterSheet(
    filter: MessageFilter,
    feed: Feed,
    onSelectCategory: (MessageCategory?) -> Unit,
    onSelectStatus: (StatusFilter) -> Unit,
    onSelectWindow: (TimeWindow) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    FilterSheet(
        title = "More filters",
        doneLabel = "Show ${feed.visibleCount} ${if (feed.visibleCount == 1) "message" else "messages"}",
        canReset = filter.hasExtras,
        onReset = onReset,
        onDismiss = onDismiss
    ) {
        SheetSection("Category")
        TileGrid(
            buildList {
                add(TileOption("All categories", filter.category == null, { onSelectCategory(null) }))
                feed.categories.forEach { (category, count) ->
                    add(TileOption(category.displayString, filter.category == category, { onSelectCategory(category) }, "$count"))
                }
            }
        )

        SheetSection("Status")
        TileGrid(StatusFilter.entries.map { TileOption(it.label, filter.status == it, { onSelectStatus(it) }) })

        SheetSection("Time")
        TileGrid(TimeWindow.entries.map { TileOption(it.label, filter.window == it, { onSelectWindow(it) }) })
    }
}
