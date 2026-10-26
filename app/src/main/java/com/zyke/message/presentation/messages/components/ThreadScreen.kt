package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.conversation.Thread
import com.zyke.message.domain.feed.SectionId
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.MessagesContract.State
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every message from one sender, newest first. Tapping a card expands it in place;
 * the message the user came from is expanded and scrolled into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    thread: Thread,
    state: State,
    actions: FeedActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
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

    Column(modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clickable(onClickLabel = "Back", onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        modifier = Modifier.size(24.dp),
                        painter = painterResource(R.drawable.baseline_arrow_back_24),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                        contentDescription = "Back"
                    )
                }
                MerchantChip(conversation.merchant.logo, description = null, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    RowTitle(conversation.title())
                    Caption("${DisplayFormatters.count(conversation.messages.size, "message", "messages")} · ${conversation.address}")
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                    MessageItem(
                        item = item.newest,
                        state = state,
                        actions = actions,
                        openOnTap = false,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}
