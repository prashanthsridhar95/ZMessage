package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.ai.AiDigest
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiUnusual
import com.zyke.message.domain.ai.AiWhy
import com.zyke.message.domain.ai.UnusualFacts
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.insights.DailyDigest
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.dashboard.components.InsightCard
import com.zyke.message.presentation.theme.Spacing
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/*
 * The five on-demand surfaces added after the first five. Each is hidden unless its own switch (and the master switch) is on and the
 * phone can do it, each asks only when the person taps (except the digest line, prepared quietly while the app is open), and each
 * answer has passed its guard in :domain before it gets here. Nothing is applied without a tap on a normal button.
 */

/** In the name box: "Suggest a clean name" for a messy payee. The suggestion goes into the box only when the person taps it; Save is still theirs. */
@Composable
internal fun AiNameIdea(raw: String, onPick: (String) -> Unit) {
    if (!rememberAiVisible(AiFeature.NAMES)) return
    val ai = rememberAi()
    val ready = rememberAiReady()
    val kept by ai.kept.collectAsState()
    val known = remember(raw, kept) { ai.knownName(raw) }
    AiThinkBlock(
        key = raw,
        startLabel = "Suggest a clean name",
        known = known,
        ready = ready,
        ask = { ai.suggestName(raw) },
        footnote = "Tidied from the name in the message, on this phone. Only the name box changes, and only when you tap Save.",
        useLabel = { "Use “$it”" },
        onUse = onPick
    )
}

/** In a message's details: "Explain this message" in plain words, on request. Never for a one-time code. */
@Composable
internal fun AiExplainRow(message: ClassifiedMessage) {
    if (!rememberAiVisible(AiFeature.EXPLAIN)) return
    val ai = rememberAi()
    if (!ai.canExplain(message)) return
    val ready = rememberAiReady()
    val kept by ai.kept.collectAsState()
    val sender = message.message.sender
    val body = message.message.body
    val known = remember(message.id, kept) { ai.knownExplanation(sender, body) }
    AiThinkBlock(
        key = message.id,
        startLabel = "Explain this message",
        known = known,
        ready = ready,
        ask = { ai.explain(sender, body) },
        footnote = "Written by the AI on this phone. Every figure in it is in the message above. It can be wrong: the message is the truth."
    )
}

/** On Insights, when spending is above last month at this point: the biggest reasons, worked out by the app, and one sentence to say them. */
@Composable
internal fun WhyUpCard(summary: MonthlySummary, messages: List<ClassifiedMessage>) {
    val ai = rememberAi()
    val ready = rememberAiReady()
    val kept by ai.kept.collectAsState()
    val facts = remember(summary, messages) { AiWhy.facts(summary, messages, ZoneId.systemDefault()) } ?: return
    val known = remember(facts.key, kept) { ai.knownWhy(facts) }
    InsightCard("Why did this go up?", subtitle = "Worked out on this phone from your own figures", summary = "On-device AI") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(facts.plain, style = MaterialTheme.typography.bodyLarge)
            facts.rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    PrivateAmount(row.shortText, Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            AiThinkBlock(
                key = facts.key,
                startLabel = "Say it in a sentence",
                known = known,
                ready = ready,
                ask = { ai.why(facts) },
                footnote = "By the AI on this phone. The figures above are the app's; the sentence uses no other number."
            )
        }
    }
}

/** In the review pile, under an unusually large payment: the app's own comparison, and one sentence from the AI on request. */
@Composable
internal fun AiUnusualRow(message: ClassifiedMessage) {
    if (!rememberAiVisible(AiFeature.UNUSUAL)) return
    val ai = rememberAi()
    val ready = rememberAiReady()
    val kept by ai.kept.collectAsState()
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    var facts by remember(message.id) { mutableStateOf<UnusualFacts?>(null) }
    LaunchedEffect(message.id) {
        val all = container.observeMessages().first()
        facts = withContext(Dispatchers.Default) { AiUnusual.facts(message, all, System.currentTimeMillis()) }
    }
    val shown = facts
    if (shown != null) {
        val known = remember(shown.key, kept) { ai.knownUnusual(shown) }
        Column(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(shown.plain, style = MaterialTheme.typography.bodyMedium)
            AiThinkBlock(
                key = shown.key,
                startLabel = "Say why in a sentence",
                known = known,
                ready = ready,
                ask = { ai.unusual(shown) },
                footnote = "By the AI on this phone, from the comparison above. It does not say whether the payment is right."
            )
        }
    }
}

/**
 * While the app is open and the digest line is switched on, prepares the line for the next morning's digest, so the notification can just
 * read it. The digest is rebuilt for that morning with the same code that posts it; if anything changes before then the stored line no
 * longer matches and the digest goes out as it always did. Waits a little after the app opens so it never competes with what the person is doing.
 */
@Composable
fun AiDigestPrep() {
    val ai = rememberAi()
    val settings by ai.settings.collectAsState()
    val ready = rememberAiReady()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val on = settings.isOn(AiFeature.DIGEST) && !ai.demo
    LaunchedEffect(on, ready, lifecycle) {
        if (on && ready) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(PREP_DELAY_MS)
                if (container.settings.settings.value.morningDigest) {
                    val zone = ZoneId.systemDefault()
                    val nowLocal = java.time.LocalDateTime.now(zone)
                    val day = if (nowLocal.hour < DIGEST_HOUR) LocalDate.now(zone) else LocalDate.now(zone).plusDays(1)
                    val morning = day.atTime(DIGEST_HOUR, 0).atZone(zone).toInstant()
                    val facts = withContext(Dispatchers.Default) {
                        val messages = container.observeMessages().first()
                        AiDigest.facts(
                            DailyDigest.build(
                                messages, container.userMessageState.state.value, morning, zone,
                                container.reminders.reminders.value, container.calendarEvents.events.value
                            )
                        )
                    }
                    if (facts != null) ai.prepareDigest(facts)
                }
            }
        }
    }
}

private const val PREP_DELAY_MS = 20_000L
private const val DIGEST_HOUR = 8
