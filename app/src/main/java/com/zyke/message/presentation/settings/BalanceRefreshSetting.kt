package com.zyke.message.presentation.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.balance.BalanceRefresh
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.rememberTick
import com.zyke.message.presentation.common.toggleRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Refresh balances before my digest" (Settings, Reminders and alerts, under the morning digest; off by default). The description is
 * the consent: these are real texts from the person's SIM and may cost money. Turning it on asks for the permission to send texts
 * (the same one Check balance uses) and stays off when it is refused. Below the switch, what the last refresh did for each account.
 */
@Composable
internal fun BalanceRefreshSetting(enabled: Boolean, digestOn: Boolean, accounts: List<AccountSummary>, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val lastRun by container.balanceCheck.refreshRun.collectAsStateWithLifecycle()
    val tick = rememberTick()
    val asker = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onChange(granted) }
    val on = enabled && digestOn
    fun change(wanted: Boolean) {
        tick()
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!wanted || allowed) onChange(wanted) else asker.launch(Manifest.permission.SEND_SMS)
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (digestOn) Modifier.toggleRow(on, Role.Switch, ::change) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Refresh balances before my digest", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 3)
                Caption(
                    if (digestOn) "About 5 minutes before the digest, Zyke texts your bank's balance number from your SIM, once a day at most, " +
                        "only for banks you already confirmed under Check balance. These are real texts and may cost money on some plans."
                    else "Turn on the morning digest first. Then, about 5 minutes before it, Zyke texts your bank's balance number from your SIM. " +
                        "These are real texts and may cost money on some plans.",
                    maxLines = 8
                )
            }
            Switch(checked = on, onCheckedChange = null, enabled = digestOn, modifier = Modifier.padding(start = Spacing.md))
        }
        val latest = lastRun
        if (on && latest != null && latest.entries.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            val day = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()).format(Instant.ofEpochMilli(latest.ranAt).atZone(zone))
            val now = System.currentTimeMillis()
            Caption("LAST REFRESH · $day", Modifier.padding(top = Spacing.md), maxLines = 1)
            latest.entries.forEach { entry ->
                val balanceAt = accounts.firstOrNull { it.key == entry.key }?.balanceAt ?: 0L
                val outcome = BalanceRefresh.outcome(entry, balanceAt, now)
                Column(Modifier.padding(top = Spacing.sm)) {
                    Text(entry.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Caption(BalanceRefresh.logLine(entry, outcome, balanceAt, zone), maxLines = 3)
                }
            }
        }
    }
}
