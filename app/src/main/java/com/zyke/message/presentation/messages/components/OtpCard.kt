package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DisplayFormatters

/**
 * Tap anywhere to copy the code; long-press for the full message.
 * Expired codes are dimmed and a tap opens the message instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OtpCard(
    item: ClassifiedMessage,
    content: MessageContent.Otp,
    time: String,
    isUnread: Boolean,
    isExpired: Boolean,
    onExpand: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val canCopy = content.code.isNotEmpty() && !isExpired
    val hint = when {
        isExpired -> "Expired"
        canCopy -> "Tap to copy"
        else -> null
    }

    MessageRow(
        onClick = onExpand,
        modifier = modifier.alpha(if (isExpired) 0.5f else 1f),
        verticalAlignment = Alignment.Top,
        clickModifier = Modifier.combinedClickable(
            onClickLabel = if (canCopy) "Copy code" else "Show message",
            onClick = { if (canCopy) onCopy(content.code) else onExpand() },
            onLongClickLabel = "Show message",
            onLongClick = onExpand
        )
    ) {
        MerchantChip(item.merchant.logo, description = content.senderName, isUnread = isUnread)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            OtpCode(content.code)

            if (content.code.isEmpty()) {
                Caption(
                    text = item.message.body,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2
                )
            }

            Caption(
                text = listOfNotNull(content.senderName, hint, time).joinToString(" · "),
                modifier = Modifier.padding(top = 4.dp)
            )

            val paymentAmount = content.paymentAmount
            val paymentCard = content.cardNumber
            if (paymentAmount != null && paymentCard != null) {
                Caption("For ${DisplayFormatters.rupees(paymentAmount)} on card •• $paymentCard")
            }
        }

        if (canCopy) {
            Image(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.baseline_content_copy_24),
                contentDescription = null,
                colorFilter = ColorFilter.tint(mutedColor())
            )
        }
    }
}

/** One box per digit, or a single box for long alphanumeric codes. */
@Composable
internal fun OtpCode(code: String, modifier: Modifier = Modifier) {
    val borderColor = MaterialTheme.colorScheme.secondary
    val textColor = MaterialTheme.colorScheme.primary

    if (code.length > 8) {
        Box(modifier.border(0.5.dp, borderColor)) {
            Text(
                modifier = Modifier.padding(horizontal = 8.dp),
                text = code,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                color = textColor
            )
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            code.forEach { char ->
                Box(
                    modifier = Modifier.width(28.dp).height(38.dp).border(0.5.dp, borderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char.toString(),
                        maxLines = 1,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center,
                        color = textColor
                    )
                }
            }
        }
    }
}
