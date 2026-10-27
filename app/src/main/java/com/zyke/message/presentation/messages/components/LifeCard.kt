package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.scaledLines
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
    MessageRow(onClick, modifier) {
        MerchantChip(
            item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name,
            description = content.title, isUnread = isUnread
        )
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RowTitle(content.title)
            Caption(
                listOfNotNull(content.kind.label, content.reference?.let { "#$it" }, time).joinToString(" · ")
            )
        }

        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = content.status,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (content.isDone) mutedColor() else MaterialTheme.colorScheme.primary,
                maxLines = scaledLines(1)
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
            .padding(top = 4.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
    val clock = content.time?.let { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(it) }
    return listOfNotNull(day, clock).joinToString(" · ")
}

/** The card read aloud: "Appointment from Apollo Clinic. Appointment confirmed. Tomorrow · 4:30 PM". */
internal fun lifeSpoken(content: MessageContent.Life, today: LocalDate): String =
    listOfNotNull(
        "${content.kind.label} from ${content.title}",
        content.status,
        content.date?.let { whenLabel(it, content, today) },
        content.reference?.let { "reference $it" }
    ).joinToString(". ")
