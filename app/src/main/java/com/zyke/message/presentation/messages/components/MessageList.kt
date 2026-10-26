package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.messages.MessagesContract.DaySection
import com.zyke.message.domain.model.MessageCategory

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageList(
    sections: List<DaySection>,
    expandedIds: Set<Long>,
    showFilters: Boolean,
    selectedFilter: MessageCategory?,
    onSelectFilter: (MessageCategory?) -> Unit,
    onToggleExpanded: (Long) -> Unit,
    onCopyOtp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier, state = rememberLazyListState()) {
        if (showFilters) {
            stickyHeader(key = "filters") {
                FilterBar(selectedFilter, onSelectFilter, Modifier.animateItemPlacement())
            }
        }

        sections.forEach { section ->
            stickyHeader(key = "day-${section.date}") {
                DayHeader(DateFormatters.dayHeader(section.date), Modifier.animateItemPlacement())
            }

            items(section.messages, key = { it.id }) { item ->
                MessageItem(
                    item = item,
                    isExpanded = item.id in expandedIds,
                    onToggleExpanded = { onToggleExpanded(item.id) },
                    onCopyOtp = onCopyOtp,
                    modifier = Modifier.animateItemPlacement()
                )
            }
        }
    }
}

@Composable
private fun DayHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        text = text,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
        fontWeight = FontWeight.Bold
    )
}
