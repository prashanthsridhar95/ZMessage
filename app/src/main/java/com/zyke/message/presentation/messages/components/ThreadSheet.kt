package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.conversation.Thread
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.MessagesContract.State
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything one sender has sent, newest first, in a bottom sheet over the Inbox. Tapping a message opens
 * its own detail sheet on top; closing that returns here. When opened from a message, the sheet scrolls to it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadSheet(
    thread: Thread,
    state: State,
    actions: FeedActions,
    onDismiss: () -> Unit
) {
    val conversation = thread.conversation
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val listState = rememberLazyListState()

    // Index of the focused message once section headers are counted in.
    val focusIndex = remember(thread, state.focusMessageId) {
        var index = 0
        var found = -1
        loop@ for (section in thread.sections) {
            index++ // header
            for (item in section.items) {
                if (item.newest.id == state.focusMessageId) {
                    found = index
                    break@loop
                }
                index++
            }
        }
        found
    }
    LaunchedEffect(conversation.key, state.focusMessageId) {
        if (focusIndex >= 0) listState.scrollToItem(focusIndex)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MerchantChip(conversation.merchant.logo, description = null, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(conversation.title())
                Caption("${DisplayFormatters.count(conversation.messages.size, "message", "messages")} · ${conversation.address}")
            }
        }
        HorizontalDivider(
            Modifier.padding(top = 8.dp).padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        )

        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            thread.sections.forEach { section ->
                stickyHeader(key = "thread-${section.id}") {
                    SectionHeader(
                        when (val id = section.id) {
                            SectionId.Pinned -> "Pinned"
                            is SectionId.Day -> DateFormatters.dayHeader(id.date, today)
                        }
                    )
                }
                items(section.items, key = { it.key }) { item ->
                    MessageItem(item = item.newest, state = state, actions = actions, modifier = Modifier.animateItem())
                }
            }
        }
    }
}
