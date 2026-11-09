package com.zyke.message.presentation.messages.components

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyke.message.data.sms.MmsImages
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Captured from a messaging app's notification (a chat, rich or not); a Google Pay top-up kept the same way is not a chat. */
internal fun SmsMessage.isChat(): Boolean = isRcs && !com.zyke.message.domain.classification.UpiLiteNotification.isFromNotification(sender)

/** What the heading of the original text says about where the message came from. */
internal fun SmsMessage.sourceHeading(): String = when {
    isMms -> "PICTURE MESSAGE (MMS)"
    isChat() -> "RICH MESSAGE (RCS)"
    else -> "MESSAGE"
}

/** The sentence under a chat (RCS) message: only its text was captured. */
internal const val RICH_NOT_AVAILABLE = "Image and buttons from the original message are not available here. Open it in Messages."

/** A small "MMS" or "RCS" tag for a message that did not arrive as a plain text; nothing for an SMS. */
@Composable
internal fun SourceTag(message: SmsMessage, modifier: Modifier = Modifier) {
    val label = when {
        message.isMms -> "MMS"
        message.isChat() -> "RCS"
        else -> return
    }
    val color = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        modifier = modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

/**
 * The pictures of a picture (MMS) message, read when its detail is open: off the main thread, shrunk while read, at most a few,
 * straight from the phone's MMS provider.
 */
@Composable
internal fun MmsPictures(messageId: Long) {
    val resolver = LocalContext.current.contentResolver
    val pictures by produceState<List<ImageBitmap>?>(null, messageId) {
        value = withContext(Dispatchers.IO) { MmsImages.load(resolver, messageId).map { it.asImageBitmap() } }
    }
    val shown = pictures ?: return
    if (shown.isEmpty()) {
        Caption("The picture in this message could not be read.", maxLines = 2)
        return
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        shown.forEach {
            Image(
                bitmap = it,
                contentDescription = "Picture in this message",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/** The honest note under a chat (RCS) message, with the way to the full message: the phone's messaging app. */
@Composable
internal fun ChatNote() {
    val context = LocalContext.current
    Caption(RICH_NOT_AVAILABLE, Modifier.padding(top = 8.dp), maxLines = 4)
    AppButton(
        "Open in Messages",
        {
            // The phone's messaging app, whichever it is; no package list is read.
            runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        Modifier.padding(top = 4.dp),
        style = ButtonStyle.TEXT
    )
}
