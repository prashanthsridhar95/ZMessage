package com.zyke.message.presentation.settings

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
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
import com.zyke.message.data.diag.InboxAuditRunner
import com.zyke.message.data.diag.LogShare
import com.zyke.message.domain.diagnostics.InboxAuditReport
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Developer tools: "Inbox audit". Counts what the phone holds (SMS inbox, picture messages, chat notifications), how many rows the All
 * tab shows, how many messages sit inside another row, and how many are hidden by each reason, then lists the twenty newest rows with
 * what became of each. For when messages in the Messages app seem to be missing in Zyke. No message text; senders are masked. Shown only
 * with developer options on and never in Demo mode. Nothing is sent anywhere unless the person shares the report.
 */
@Composable
internal fun InboxAuditCard() {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    if (container.demo) return
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<InboxAuditReport?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.systemDefault() }

    SectionCard("Inbox audit") {
        Caption(
            "Developer tool. Counts the messages on the phone and says where each one is in Zyke, or why it is not shown. No message text is read out or shared; senders are masked.",
            maxLines = 4
        )
        AppButton(
            if (running) "Counting…" else "Run audit",
            {
                running = true; note = null
                scope.launch {
                    report = InboxAuditRunner.run(container)
                    if (report == null) note = "Could not read the inbox: allow SMS access first."
                    running = false
                }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running
        )
        note?.let { Caption(it, Modifier.padding(top = 8.dp), maxLines = 3) }
        val shown = report
        if (shown != null) {
            shown.toText(zone).lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val heading = !line.startsWith(" ")
                Text(
                    line.trim(), Modifier.padding(top = if (heading) 12.dp else 2.dp),
                    style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    color = if (heading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }
        }
        AppButton(
            "Share audit",
            {
                val text = report?.toText(zone)
                if (text == null) {
                    Toast.makeText(context, "Run the audit first", Toast.LENGTH_SHORT).show()
                } else {
                    val name = "zyke-inbox-audit-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"
                    if (LogShare.shareText(context, name, "Zyke Message inbox audit", text) == LogShare.Result.FAILED) {
                        Toast.makeText(context, "Could not share the audit", Toast.LENGTH_LONG).show()
                    }
                }
            },
            Modifier.fillMaxWidth().padding(top = 12.dp), enabled = report != null && !running
        )
    }
}
