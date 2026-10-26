package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.R
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent

@Composable
internal fun OtpCard(
    item: ClassifiedMessage,
    content: MessageContent.Otp,
    onClick: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    MessageRow(onClick, modifier, verticalAlignment = Alignment.Top) {
        MerchantChip(item.merchant.logo)
        Spacer(Modifier.width(16.dp))

        Column(verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OtpCode(content.code)
                Spacer(Modifier.width(16.dp))
                CopyButton(onClick = { onCopy(content.code) })
            }

            Row(Modifier.padding(top = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    text = content.senderName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                CategoryTag(MessageCategory.OTP)
            }

            if (content.code.isEmpty()) {
                Text(
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    text = item.message.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (content.paymentAmount != null && content.cardNumber != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "For payment of ₹${content.paymentAmount}",
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = mutedColor()
                    )
                    IconCaption(R.drawable.credit_card, "•• ${content.cardNumber}")
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun OtpCode(code: String) {
    val borderColor = MaterialTheme.colorScheme.secondary
    val textColor = MaterialTheme.colorScheme.primary

    // Long alphanumeric codes (e.g. Simpl) don't fit one box per character.
    if (code.length > 8) {
        Box(Modifier.border(0.5.dp, borderColor)) {
            Text(
                modifier = Modifier.padding(horizontal = 8.dp),
                text = code,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                color = textColor
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            code.forEach { char ->
                Box(
                    modifier = Modifier.width(32.dp).height(40.dp).border(0.5.dp, borderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char.toString(),
                        maxLines = 1,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            modifier = Modifier.size(16.dp),
            painter = painterResource(R.drawable.baseline_content_copy_24),
            contentDescription = "Copy code",
            colorFilter = ColorFilter.tint(mutedColor())
        )
        Text(text = "Copy", color = mutedColor(), fontSize = 10.sp)
    }
}
