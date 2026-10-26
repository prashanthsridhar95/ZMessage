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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.MessageCategory

@Composable
fun MessagesTopBar(
    filtersVisible: Boolean,
    onToggleFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                modifier = Modifier.height(36.dp).padding(horizontal = 16.dp),
                painter = painterResource(R.drawable.zyke_main),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                contentDescription = "Zyke"
            )

            Image(
                modifier = Modifier
                    .clickable(onClick = onToggleFilters)
                    .height(36.dp)
                    .padding(horizontal = 16.dp),
                painter = painterResource(R.drawable.baseline_filter_alt_24),
                colorFilter = ColorFilter.tint(
                    if (filtersVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                ),
                contentDescription = "Filter"
            )
        }

        HorizontalDivider(
            Modifier.padding(top = 8.dp).padding(horizontal = 8.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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
                    Icon(
                        painter = painterResource(R.drawable.baseline_message_24),
                        contentDescription = null
                    )
                },
                label = { Text("Messages") }
            )
        }
    }
}

@Composable
fun FilterBar(
    selected: MessageCategory?,
    onSelect: (MessageCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        LazyRow(
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
        ) {
            item(key = "all") {
                FilterChip("All", selected == null) { onSelect(null) }
            }
            items(MessageCategory.entries, key = { it.name }) { category ->
                FilterChip(category.displayString, selected == category) { onSelect(category) }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun FilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val frame = if (isSelected) {
        Modifier.background(MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier.border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), shape)
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(onClick = onClick)
            .height(36.dp)
            .then(frame)
            .padding(horizontal = 8.dp),
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
