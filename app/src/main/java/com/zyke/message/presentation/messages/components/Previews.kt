package com.zyke.message.presentation.messages.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.feed.MessageFilter
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserMessageState
import com.zyke.message.presentation.messages.MessagesContract.State
import com.zyke.message.presentation.theme.ZykeMessageTheme
import java.time.Instant
import java.time.ZoneId

private val classifier = MessageClassifier()
private var nextId = 1L

private fun sample(body: String, sender: String, minutesAgo: Long = 0, daysAgo: Long = 0, read: Boolean = true): ClassifiedMessage {
    val date = Instant.now().minusSeconds(minutesAgo * 60 + daysAgo * 86_400).toEpochMilli()
    return classifier.classify(SmsMessage(nextId++, sender, body, date, read))
}

private fun sampleState(): State {
    val messages = listOf(
        sample("482913 is your OTP for login to Swiggy. Do not share.", "AD-SWIGGY", minutesAgo = 2, read = false),
        sample("Sent Rs.1250.00\nFrom HDFC Bank A/C x1234\nTo friend@ybl\nOn 12/01/25\nRef 123456", "AD-HDFCBK", minutesAgo = 40),
        sample("Credit Alert!\nRs.9000.00 credited to HDFC Bank A/c xx9876 on 01-02-25 from VPA salary@okaxis (UPI 123456789)", "AD-HDFCBK", minutesAgo = 90),
        sample("HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:${java.time.LocalDate.now().plusDays(2).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))} pay https://hdfcbk.io/x", "AD-HDFCBK", minutesAgo = 300),
        sample("Your FASTag vehicle KA01AB1234 is low on balance", "AD-FASTAG", daysAgo = 1),
        sample("Blue Dart shipment ARRIVING TODAY", "VM-BLUDRT", daysAgo = 1),
        sample("80% Alert: You have consumed 80% of your daily high Speed Data limit. Track usage on Airtel Thanks App i.airtel.in/Get-Data", "AIRTEL", daysAgo = 2),
        sample("Hey, are we still on for dinner tomorrow?", "+919999999999", daysAgo = 2)
    )
    val now = Instant.now()
    val feed = FeedBuilder.build(messages, UserMessageState(), MessageFilter.All, "", now, ZoneId.systemDefault())
    return State(messages = messages, now = now, feed = feed)
}

@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    ZykeMessageTheme(dynamicColor = false) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

@Preview(name = "Feed", showBackground = true, heightDp = 900)
@Composable
private fun FeedPreview() = PreviewHost { MessageList(sampleState(), FeedActions.None) }

@Preview(name = "Feed (dark)", showBackground = true, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FeedDarkPreview() = PreviewHost { MessageList(sampleState(), FeedActions.None) }

@Preview(name = "Loading", showBackground = true)
@Composable
private fun SkeletonPreview() = PreviewHost { MessageListSkeleton() }
