package com.zyke.message.presentation.settings

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.diag.LiveReplyMemo
import com.zyke.message.data.diag.LogShare
import com.zyke.message.domain.diag.LiveCheckReport
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Developer tools on the Flights and trains page: one real request to the live train or flight service for a sample number, with a card
 * saying what came back and why a failure failed, and a way to share the raw reply with the developer. Shown only with developer options
 * on and never in Demo mode. The requests go through the same fetchers as the cards (via the two services) and carry only the sample
 * train number or airline code and flight number, never anything of the person's. The last raw reply is held in memory only.
 */
@Composable
internal fun LiveStatusChecks() {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    if (container.demo) return
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<LiveCheckReport?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var shareable by remember { mutableStateOf(LiveReplyMemo.shareText() != null) }

    SectionCard("Live status check") {
        Caption(
            "Developer tool. Makes one real request for a sample train (12633) or flight (6E 2345) and shows what came back. Only the sample number is sent. Share the raw reply with the developer if status does not load.",
            maxLines = 5
        )
        AppButton(
            if (running) "Checking…" else "Test train status",
            {
                running = true; note = null
                scope.launch {
                    report = container.trains.diagnose(SAMPLE_TRAIN)
                    if (report == null) note = "Nothing was sent."
                    shareable = LiveReplyMemo.shareText() != null
                    running = false
                }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running
        )
        AppButton(
            if (running) "Checking…" else "Test flight status",
            {
                running = true; note = null
                scope.launch {
                    report = container.flights.diagnose(SAMPLE_FLIGHT, ZoneId.systemDefault())
                    if (report == null) note = "Nothing was sent."
                    shareable = LiveReplyMemo.shareText() != null
                    running = false
                }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running
        )
        val shown = report
        if (shown != null) {
            shown.lines().forEachIndexed { index, line ->
                Text(
                    line, Modifier.padding(top = if (index == 0) 12.dp else 4.dp),
                    style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    color = if (index == 0 || !shown.diagnosis.ok && line.startsWith("Result")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
        }
        note?.let { Caption(it, Modifier.padding(top = 8.dp)) }
        AppButton(
            "Share raw reply",
            {
                val text = LiveReplyMemo.shareText()
                if (text == null) {
                    Toast.makeText(context, "Run a test first", Toast.LENGTH_SHORT).show()
                } else {
                    val name = "zyke-live-status-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"
                    if (LogShare.shareText(context, name, "Zyke Message live status sample", text) == LogShare.Result.FAILED) {
                        Toast.makeText(context, "Could not share the reply", Toast.LENGTH_LONG).show()
                    }
                }
            },
            Modifier.fillMaxWidth().padding(top = 12.dp), enabled = shareable && !running
        )
        Caption("The reply is public status data for the sample number, kept in memory only until the app closes, cut at 64 KB.", Modifier.padding(top = 8.dp), maxLines = 3)
    }
}

private const val SAMPLE_TRAIN = "12633"
private const val SAMPLE_FLIGHT = "6E 2345"
