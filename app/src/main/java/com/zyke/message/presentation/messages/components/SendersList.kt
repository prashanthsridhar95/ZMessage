package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.conversation.Conversation
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import java.time.LocalDate

/** The inbox grouped by sender, latest conversation first. */
@Composable
fun SendersList(
    conversations: List<Conversation>,
    today: LocalDate,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier) {
        items(conversations, key = { it.key }, contentType = { "sender" }) { conversation ->
            SenderRow(conversation, today) { onOpen(conversation.key) }
        }
    }
}

internal fun Conversation.title(): String =
    if (merchant.logo != MerchantLogo.PERSON) merchant.displayName else address

@Composable
private fun SenderRow(conversation: Conversation, today: LocalDate, onClick: () -> Unit) {
    val newest = conversation.newest.message
    val stamp = remember(newest.date, today) { DisplayFormatters.listStamp(newest.date, today = today) }
    val preview = remember(newest.body) { newest.body.replace(Regex("\\s+"), " ").trim() }
    val unread = conversation.unreadCount

    MessageRow(onClick = onClick) {
        MerchantChip(conversation.merchant.logo, description = conversation.title(), isUnread = false)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowTitle(conversation.title(), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Caption(stamp, color = if (unread > 0) MaterialTheme.colorScheme.primary else mutedColor())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(
                    text = preview,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadCount(unread)
                }
            }
            Caption(DisplayFormatters.count(conversation.messages.size, "message", "messages"), Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun UnreadCount(count: Int) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

