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
import com.zyke.message.data.diag.TrainProbeMemo
import com.zyke.message.data.markets.TrainProbe
import com.zyke.message.domain.diag.LiveCheckReport
import com.zyke.message.domain.diag.ProbeResult
import com.zyke.message.domain.diag.TrainProbeFormat
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var probing by remember { mutableStateOf(false) }
    var probeResults by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }
    var probeTotal by remember { mutableStateOf(0) }
    var probeShareable by remember { mutableStateOf(TrainProbeMemo.shareText() != null) }

    SectionCard("Live status check") {
        Caption(
            "Developer tool. Makes one real request for a sample train (12676) or flight (6E 2345) and shows what came back. Only the sample number is sent. Share the raw reply with the developer if status does not load.",
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
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running && !probing
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
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running && !probing
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
            Modifier.fillMaxWidth().padding(top = 12.dp), enabled = shareable && !running && !probing
        )
        Caption("The reply is public status data for the sample number, kept in memory only until the app closes, cut at 64 KB.", Modifier.padding(top = 8.dp), maxLines = 3)

        Caption(
            "Probe train sources: tries a short list of public train status addresses for the sample train, one after another (6 s each, no retries), to find one that works. Only the sample number is sent. Redirects are not followed.",
            Modifier.padding(top = 16.dp), maxLines = 5
        )
        ResponsiveRow(Modifier.fillMaxWidth().padding(top = 8.dp), spacing = 8.dp) {
            AppButton(
                if (probing) "Probing ${probeResults.size + 1} of $probeTotal…" else "Probe train sources",
                {
                    probing = true; probeResults = emptyList()
                    scope.launch {
                        val probe = TrainProbe()
                        val list = probe.candidates(SAMPLE_TRAIN).orEmpty()
                        probeTotal = list.size
                        val collected = mutableListOf<ProbeResult>()
                        list.forEachIndexed { i, candidate ->
                            val result = withContext(Dispatchers.IO) { probe.probe(i + 1, candidate) }
                            collected += result
                            probeResults = collected.toList()
                            TrainProbeMemo.put(collected, SAMPLE_TRAIN)
                        }
                        probeShareable = TrainProbeMemo.shareText() != null
                        probing = false
                    }
                },
                Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = !probing && !running
            )
            AppButton(
                "Share raw replies",
                {
                    val text = TrainProbeMemo.shareText()
                    if (text == null) {
                        Toast.makeText(context, "Run a probe first", Toast.LENGTH_SHORT).show()
                    } else {
                        val name = "zyke-train-probe-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"
                        if (LogShare.shareText(context, name, "Zyke Message train source probe", text) == LogShare.Result.FAILED) {
                            Toast.makeText(context, "Could not share the replies", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = probeShareable && !probing
            )
        }
        probeResults.forEach { r ->
            val lines = TrainProbeFormat.resultLines(r)
            val good = r.statusCode in 200..299
            Text(
                lines[0], Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall,
                color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(lines[1], Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text(
                lines[2], Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary, maxLines = 3
            )
        }
        Caption("Probe replies are public data for the sample number, kept in memory only until the app closes, each cut at 16 KB.", Modifier.padding(top = 8.dp), maxLines = 3)
    }
}

private const val SAMPLE_TRAIN = "12676"
private const val SAMPLE_FLIGHT = "6E 2345"
