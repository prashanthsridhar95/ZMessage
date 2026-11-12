package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.conversation.SenderLabel
import com.zyke.message.domain.sharing.ReceivedCodeSenders
import com.zyke.message.domain.sharing.ReceivedSender
import com.zyke.message.domain.sharing.ShareSource
import com.zyke.message.domain.sharing.ShareSources
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.SearchField
import com.zyke.message.presentation.common.SenderTitle
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The senders that texted a code in the last 90 days, built from the live message list (see [ReceivedCodeSenders]). The list is only
 * read while [enabled] (the picker is open), so the page itself costs nothing. Null while it is still being worked out.
 */
@Composable
internal fun rememberReceivedSenders(enabled: Boolean, custom: List<ShareSource>): State<List<ReceivedSender>?> {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    return produceState<List<ReceivedSender>?>(initialValue = null, enabled, custom) {
        if (!enabled) return@produceState
        container.observeMessages.calm(2_000L).collect { messages ->
            value = withContext(Dispatchers.Default) { ReceivedCodeSenders.build(messages, System.currentTimeMillis(), custom) }
        }
    }
}

/** A source in a form that survives rotation: the sheet keeps the senders picked but not saved yet this way. */
internal fun encodeSource(s: ShareSource): String = listOf(s.id, s.label, s.key, s.header).joinToString("\t")

internal fun decodeSource(raw: String): ShareSource? {
    val p = raw.split('\t')
    return if (p.size == 4 && p[0].startsWith(ShareSources.ID_PREFIX)) ShareSource(p[0], p[1], p[2], false, p[3]) else null
}

/**
 * "Choose from codes you've received": every sender that texted a login code lately, newest first, with a switch for [selected]
 * (source ids). Searchable. A bank, payment or similar sender is listed greyed at the bottom with the reason and cannot be switched on.
 * The list holds names and counts only, never a code or a message.
 */
@Composable
internal fun ReceivedSendersSheet(
    title: String,
    subtitle: String?,
    senders: List<ReceivedSender>?,
    selected: Set<String>,
    now: Long,
    onToggle: (ReceivedSender, Boolean) -> Unit,
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    AdaptiveSheet(
        title = title,
        onDismiss = onDismiss,
        subtitle = subtitle,
        autoPage = false,
        contentSpacing = Spacing.sm,
        footer = { AppButton("Done", onDismiss, Modifier.fillMaxWidth()) }
    ) {
        header?.invoke()
        when {
            senders == null -> Caption("Looking through your messages…", Modifier.padding(vertical = Spacing.lg), maxLines = 2)
            senders.isEmpty() -> EmptyState(
                "No codes received yet",
                "They appear here when a service texts you one."
            )
            else -> {
                SearchField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search senders", autoFocus = false)
                val shown = ReceivedCodeSenders.filter(senders, query)
                if (shown.isEmpty()) {
                    Caption("Nothing matches “${query.trim()}”", Modifier.padding(vertical = Spacing.md), maxLines = 2)
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    shown.forEach { sender ->
                        androidx.compose.runtime.key(sender.key) {
                            ReceivedSenderRow(sender, sender.sourceId in selected, now) { on -> onToggle(sender, on) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceivedSenderRow(sender: ReceivedSender, checked: Boolean, now: Long, onToggle: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val blocked = sender.blocked
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (blocked) 0.5f else 1f).clip(AppShapes.chip)
            .toggleable(
                value = checked && !blocked, enabled = !blocked, role = Role.Switch,
                onValueChange = { haptics.tick(); onToggle(it) }
            )
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            SenderTitle(SenderLabel(sender.displayName, sender.verified), style = MaterialTheme.typography.bodyLarge)
            sender.brandIfDifferent?.let { Caption(it, maxLines = 1) }
            Caption(ReceivedCodeSenders.summary(sender, now) + " · " + sender.header, maxLines = 2)
            if (blocked) Caption(ShareSources.BLOCKED_REASON, color = MaterialTheme.colorScheme.error, maxLines = 2)
        }
        Switch(checked = checked && !blocked, onCheckedChange = null, enabled = !blocked, modifier = Modifier.padding(start = Spacing.md))
    }
}
