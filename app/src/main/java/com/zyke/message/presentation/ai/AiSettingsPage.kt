package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.zyke.message.domain.ai.AiAvailability
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiResult
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.LearnMore
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.settings.ToggleRow
import com.zyke.message.presentation.theme.Spacing
import java.time.ZoneId
import kotlinx.coroutines.launch

/** What the person reads once, before the AI can be switched on. Plain words; the same facts are in docs/PRIVACY.md and the Privacy and terms page. */
private val EXPLANATION = listOf(
    "Everything runs on this phone. Your messages are never sent anywhere to be read by the AI, and there is no cloud fallback.",
    "The model (Gemini Nano) is downloaded and kept up to date by Google's AI Core service on the phone, not by this app. Android or Google Play services may fetch it the first time, over the internet.",
    "It can be wrong. Every suggestion is checked against the message text, and nothing is saved until you tap Use this.",
    "It never counts your money. Totals and answers are worked out by the app from your messages; the AI only reads a question or words a sentence around figures the app gives it.",
    "It needs a recent phone (Pixel 8 or later) with AI Core. On other phones this page says so and nothing changes."
)

/** Settings, "On-device AI": the master switch, the model's state, one switch per feature, a test box, and the cache. */
@Composable
internal fun AiSettingsContent() {
    val ai = rememberAi()
    val settings by ai.settings.collectAsState()
    val availability by ai.availability.collectAsState()
    val diag by ai.diagnostics.collectAsState()
    val kept by ai.kept.collectAsState()
    val haptics = rememberHaptics()
    var explaining by rememberSaveable { mutableStateOf(false) }
    val on = settings.master && settings.explained

    LaunchedEffect(Unit) { ai.refresh() }

    if (!ai.demo) FintechCard {
        ToggleRow(
            title = "Use on-device AI",
            description = "Off until you switch it on. Runs on this phone only.",
            checked = on,
            onCheckedChange = { wanted ->
                if (!wanted) {
                    ai.update { it.copy(master = false) }
                } else if (!settings.explained) {
                    explaining = true
                } else {
                    ai.update { it.copy(master = true) }
                    ai.refresh()
                }
            },
            learnMore = EXPLANATION.joinToString("\n\n")
        )
    }

    if (explaining) {
        AdaptiveSheet(
            title = "Before you switch it on",
            onDismiss = { explaining = false },
            footer = {
                ResponsiveRow {
                    AppButton("Switch on", { haptics.confirm(); ai.update { it.copy(master = true, explained = true) }; ai.refresh(); explaining = false }, Modifier.cell())
                    AppButton("Not now", { explaining = false }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                }
            }
        ) {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md)) {
                EXPLANATION.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    if (ai.demo) {
        DemoExamples()
        return
    }

    StatusCard(availability, onDownload = { ai.download() }, onCheck = { ai.refresh() })

    if (!on) {
        Caption("Switch on On-device AI to choose what it does.", Modifier.padding(horizontal = Spacing.xs), maxLines = 2)
        return
    }

    FintechCard {
        RowTitle("What it can do")
        AiFeature.entries.forEach { feature ->
            ToggleRow(
                title = feature.title,
                description = feature.summary,
                checked = feature in settings.enabled,
                onCheckedChange = { haptics.tick(); ai.update { s -> s.withFeature(feature, it) } }
            )
        }
    }

    if (AiFeature.UNDERSTAND in settings.enabled) ReviewPileCard()
    TryItCard(canTry = availability.canGenerate)

    FintechCard {
        AppButton("Clear AI suggestions cache", { ai.clearCache() }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
        Caption(
            diag.line() + " · $kept " + (if (kept == 1) "suggestion" else "suggestions") + " kept on this phone",
            Modifier.padding(top = Spacing.sm), maxLines = 3
        )
    }
}

@Composable
private fun StatusCard(availability: AiAvailability, onDownload: () -> Unit, onCheck: () -> Unit) {
    val (label, tone, detail) = when (availability) {
        AiAvailability.Unsupported -> Triple("Not supported on this phone", Tone.NEUTRAL, "Gemini Nano needs a recent Pixel (8 or later) with Google's AI Core service. Everything else in the app works as before.")
        AiAvailability.NeedsDownload -> Triple("Model not downloaded", Tone.WARN, "Google's service on the phone fetches the model. It is a large download; Wi-Fi is best.")
        is AiAvailability.Downloading -> Triple(
            "Downloading", Tone.NEUTRAL,
            availability.megabytes?.let { "$it MB so far. You can leave this page; Google's service carries on." } ?: "Google's service is fetching the model. You can leave this page."
        )
        AiAvailability.Ready -> Triple("Ready", Tone.GOOD, "The model is on this phone. A suggestion takes a few seconds and always needs your OK.")
        AiAvailability.Busy -> Triple("Busy", Tone.NEUTRAL, "Working on an answer, or the phone asked the app to wait.")
        is AiAvailability.Failed -> Triple("Problem", Tone.BAD, availability.reason)
    }
    FintechCard {
        RowTitle("The model")
        StatusPill(label, tone)
        Caption(detail, Modifier.padding(top = Spacing.sm), maxLines = 4)
        ResponsiveRow(Modifier.padding(top = Spacing.md)) {
            AppButton("Download model", onDownload, Modifier.cell(), enabled = availability == AiAvailability.NeedsDownload)
            AppButton("Check again", onCheck, Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = availability !is AiAvailability.Downloading)
        }
    }
}

@Composable
private fun ReviewPileCard() {
    val ai = rememberAi()
    val settings by ai.settings.collectAsState()
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val messages by remember(container) { container.observeMessages() }.collectAsState(initial = emptyList())
    FintechCard {
        RowTitle("Check my review pile")
        ToggleRow(
            title = "Only while charging",
            description = "Kinder to the battery",
            checked = settings.batchOnlyWhileCharging,
            onCheckedChange = { wanted -> ai.update { it.copy(batchOnlyWhileCharging = wanted) } }
        )
        AiBatchControls {
            ReviewQueues.build(messages, container.setAsides.state.value, ZoneId.systemDefault()).unclear
        }
    }
}

@Composable
private fun TryItCard(canTry: Boolean) {
    val ai = rememberAi()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    FintechCard {
        RowTitle("Try it")
        FormField("Something short to ask", text, { text = it }, Modifier.padding(top = Spacing.sm), placeholder = "Say hello in five words", imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        ResponsiveRow(Modifier.padding(top = Spacing.md)) {
            AppButton(
                if (busy) "Asking" else "Try it",
                {
                    busy = true
                    scope.launch {
                        answer = when (val r = ai.tryIt(text)) {
                            is AiResult.Text -> r.text.trim() + "  (%.1f s)".format(r.latencyMs / MS_PER_S)
                            is AiResult.Failure -> failureText(r.kind)
                        }
                        busy = false
                    }
                },
                Modifier.cell(), enabled = canTry && !busy && text.isNotBlank()
            )
            AppButton("Clear", { text = ""; answer = null }, Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = text.isNotEmpty() || answer != null)
        }
        answer?.let { Text(it, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        Caption("Only for trying it out. What you type here is not saved or logged.", Modifier.padding(top = Spacing.sm), maxLines = 2)
    }
}

private const val MS_PER_S = 1000.0

/** Demo mode never calls the model. These made-up examples show what it would do, and say so. */
@Composable
private fun DemoExamples() {
    FintechCard {
        RowTitle("Demo mode")
        Caption("The demo never uses the AI. These examples are made up, to show what it would do on a supported phone.", Modifier.padding(top = Spacing.xs), maxLines = 4)
    }
    FintechCard {
        RowTitle("Example: ask your money")
        Caption("Made-up example, not from your messages", maxLines = 1)
        Text("“how much on food last week?”", Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
        Text("You spent ₹4,820 on Food last week, across 7 payments.", Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    FintechCard {
        RowTitle("Example: month in a sentence")
        Caption("Made-up example, not from your messages", maxLines = 1)
        Text("You have spent ₹42,300 so far, 12% more than last month, mostly on Food.", Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
    }
    LearnMore("The real thing needs your own phone, a recent Pixel with AI Core, and the switch on this page. Nothing here is sent anywhere.")
}
