package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.triage.TriageMode
import com.zyke.message.domain.triage.TriageSettings
import com.zyke.message.domain.triage.TriageStackSpec
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.SegmentedChoice
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.stackTone
import com.zyke.message.presentation.theme.tonalFill

/** The one row in Settings that opens "Make it yours", the order and modes of Home's stacks. */
@Composable
internal fun HomeDeckEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard("Home deck") {
        Row(
            Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Open Make it yours") { open = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Make it yours")
                Caption("Order the stacks, and keep, quiet or clear each one", maxLines = 2)
            }
            Text("›", color = mutedColor())
        }
    }
    if (open) HomeDeckSettingsPage { open = false }
}

/**
 * "Make it yours": the stacks in the order Home shows them, each with a rank, up and down buttons and a mode. Keep stacks count in "to go";
 * Quiet stacks wait on their own line; Auto clear (only Codes and Offers offer it) clears silently into "Auto cleared". Changes are a
 * draft until Save, and Reset puts the defaults in the draft. SAFETY: no stack that holds money (payments, bills, refunds, balance
 * alerts) offers Auto clear; the domain refuses it as well, whatever is saved.
 */
@Composable
internal fun HomeDeckSettingsPage(onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repository = remember(container) { container.triage }
    val saved by repository.settings.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    val haptics = rememberHaptics()
    val changed = draft.normalized() != saved.normalized()
    AppPage(
        title = "Make it yours",
        onBack = onDismiss,
        footer = {
            ResponsiveRow(spacing = Spacing.sm) {
                AppButton("Reset to defaults", { draft = TriageSettings() }, Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = !draft.isDefault)
                AppButton("Save", { repository.save(draft); haptics.tick(); onDismiss() }, Modifier.cell(), enabled = changed)
            }
        }
    ) {
        Caption(
            "Choose what you go through first, and what waits. Payments, bills, refunds and balance alerts are never cleared for you.",
            Modifier.padding(bottom = Spacing.md), maxLines = 4
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            val ids = draft.orderedIds()
            ids.forEachIndexed { rank, id ->
                // No `return@key` here: a non-local return in this lambda makes the Kotlin 2.0 compiler emit a class D8 cannot dex.
                androidx.compose.runtime.key(id) {
                    TriageStackSpec.byId(id)?.let { spec ->
                        StackSettingsRow(
                            rank = rank + 1, spec = spec, mode = draft.modeOf(id), canUp = rank > 0, canDown = rank < ids.lastIndex,
                            onUp = { draft = draft.moved(id, -1); haptics.tick() },
                            onDown = { draft = draft.moved(id, 1); haptics.tick() },
                            onMode = { draft = draft.withMode(id, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StackSettingsRow(
    rank: Int, spec: TriageStackSpec, mode: TriageMode, canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onMode: (TriageMode) -> Unit
) {
    val tone = stackTone(spec.id)
    FintechCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$rank", Modifier.background(tone.container, AppShapes.chip).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tone.ink, textAlign = TextAlign.Center
            )
            Text(
                spec.title, Modifier.weight(1f).padding(horizontal = Spacing.md).heading(), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
            )
            MoveButton("↑", "Move ${spec.title} up", canUp, onUp)
            MoveButton("↓", "Move ${spec.title} down", canDown, onDown)
        }
        Box(Modifier.padding(top = Spacing.sm)) {
            SegmentedChoice(spec.allowed.map { Choice(it, it.label) }, mode, onMode)
        }
        if (spec.canAutoClear) {
            Caption(
                if (spec.id == TriageStackSpec.CODES) "Auto clear only takes codes that have expired." else "Auto clear only takes marketing and muted senders.",
                Modifier.padding(top = Spacing.sm), maxLines = 2
            )
        }
    }
}

/** An up or down button: 48 dp, the same for every row, dim when there is nowhere to go. */
@Composable
private fun MoveButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .tappable(onClick = onClick, enabled = enabled, onClickLabel = description)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph, Modifier.background(tonalFill(Tonal.Raised), AppShapes.chip).padding(horizontal = Spacing.md, vertical = Spacing.xs),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
    }
}
