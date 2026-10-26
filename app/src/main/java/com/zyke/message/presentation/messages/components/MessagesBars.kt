package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.model.MessageCategory

@Composable
fun MessagesTopBar(
    isSearching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    categoryBarVisible: Boolean,
    onToggleCategoryBar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearching) {
                SearchField(query, onQueryChange, Modifier.weight(1f))
                BarIcon(R.drawable.baseline_close_24, "Close search", false, onToggleSearch)
            } else {
                Image(
                    modifier = Modifier.height(36.dp).padding(horizontal = 16.dp),
                    painter = painterResource(R.drawable.zyke_main),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentDescription = "Zyke"
                )
                Row {
                    BarIcon(R.drawable.baseline_search_24, "Search", false, onToggleSearch)
                    BarIcon(R.drawable.baseline_filter_alt_24, "Filter by category", categoryBarVisible, onToggleCategoryBar)
                }
            }
        }

        HorizontalDivider(
            Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun BarIcon(icon: Int, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            modifier = Modifier.size(24.dp),
            painter = painterResource(icon),
            colorFilter = ColorFilter.tint(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            ),
            contentDescription = description
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
            Text("Search messages, merchants, amounts", color = mutedColor(), style = MaterialTheme.typography.bodyLarge)
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.primary),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}

@Composable
fun MessagesBottomBar(modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(
            Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(
                selected = true,
                onClick = {},
                icon = {
                    Icon(painter = painterResource(R.drawable.baseline_message_24), contentDescription = null)
                },
                label = { Text("Messages") }
            )
        }
    }
}

/** The handful of broad buckets; empty ones are not offered. */
@Composable
fun SmartFilterRow(
    available: List<SmartFilter>,
    selected: SmartFilter?,
    onSelect: (SmartFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(available, key = { it.name }) { filter ->
            FilterChip(filter.label, filter == selected) { onSelect(filter) }
        }
    }
}

/** Fine-grained categories, behind the filter icon. */
@Composable
fun CategoryBar(
    selected: MessageCategory?,
    onSelect: (MessageCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        LazyRow(
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item(key = "all") { FilterChip("All categories", selected == null) { onSelect(null) } }
            items(MessageCategory.entries, key = { it.name }) { category ->
                FilterChip(category.displayString, selected == category) { onSelect(category) }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
internal fun FilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val frame = if (isSelected) {
        Modifier.background(MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier.border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), shape)
    }

    // The outer box is the 48dp touch target; the visible chip is 36dp.
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Filter $label", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.height(36.dp).then(frame).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else mutedColor()
            )
        }
    }
}
