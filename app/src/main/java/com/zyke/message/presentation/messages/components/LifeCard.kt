package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.background
import com.zyke.message.presentation.format.FormatCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.scaledLines
import com.zyke.message.presentation.format.displayName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An order, trip, appointment or renewal: who it is with, where it stands, and when. */
@Composable
internal fun LifeCard(
    item: ClassifiedMessage,
    content: MessageContent.Life,
    time: String,
    isUnread: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The row says who it is with by the name the person sees: a nickname they set, or the brand found in the text when the sender is only
    // a number ("57575625" for Zoho). Otherwise the title the parser made stays.
    val title = if (item.merchant.renamed || content.title.none { it.isLetter() }) item.merchant.displayName.ifBlank { content.title } else content.title
    MessageRow(onClick, modifier) {
        MerchantChip(
            item.merchant.logo, size = RowTile, imageKey = item.merchant.imageKey, name = item.merchant.name, originalName = item.merchant.originalName,
            description = title, isUnread = isUnread
        )
        Spacer(Modifier.width(Spacing.lg))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(title)
            Caption(
                listOfNotNull(content.kind.label, content.reference?.let { "#$it" }, time).joinToString(" · ")
            )
        }

        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.widthIn(max = 140.dp), horizontalAlignment = Alignment.End) {
            Text(
                text = content.status,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (content.isDone) mutedColor() else MaterialTheme.colorScheme.primary,
                maxLines = scaledLines(1),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            content.date?.let { date ->
                DatePill(whenLabel(date, content, today), urgent = !content.isDone && date >= today && date <= today.plusDays(2))
            }
        }
    }
}

@Composable
private fun DatePill(text: String, urgent: Boolean) {
    val accent = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    Text(
        text = text,
        modifier = Modifier
            .padding(top = Spacing.xs)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(Radius.chip - 4.dp))
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        maxLines = 1
    )
}

/** "Today", "Tomorrow", "Thu 12 Nov" and the time when there is one. */
internal fun whenLabel(date: LocalDate, content: MessageContent.Life, today: LocalDate): String {
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "d MMM yyyy", Locale.getDefault()).format(date)
    }
    val clock = content.time?.let { FormatCache.pattern("h:mm a", Locale.getDefault()).format(it) }
    return listOfNotNull(day, clock).joinToString(" · ")
}
