package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.toTitleCase
import java.util.Locale

/** Expanded view of a message: full text, sender and timestamp. */
@Composable
internal fun MessageDetail(item: ClassifiedMessage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val message = item.message
    val payee = (item.content as? MessageContent.Transaction)?.payee.orEmpty()
    val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    Column(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor)
        Spacer(Modifier.height(16.dp))

        MerchantChip(item.merchant.logo)
        Spacer(Modifier.height(8.dp))

        if (payee.isNotEmpty() && payee != message.sender) {
            Text(
                text = payee.toTitleCase(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp),
                text = message.sender,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = mutedColor()
            )
        } else {
            Text(
                text = message.sender.uppercase(Locale.ROOT),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            text = message.body
        )

        Text(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp).fillMaxWidth(),
            text = DateFormatters.detailTimestamp(message.date),
            color = mutedColor(),
            textAlign = TextAlign.End
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = dividerColor)
    }
}
