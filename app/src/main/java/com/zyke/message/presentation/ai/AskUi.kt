package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.search.SearchHit
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.theme.Spacing

private const val MIN_QUESTION_LENGTH = 4

/**
 * Whether Home offers Ask Zyke: the master switch and "Ask your money" are on, this is not Demo, and the phone can do it at all.
 * In Demo the button is there too, but the page only shows made-up, labelled examples and never asks a model.
 */
@Composable
fun rememberAskHomeVisible(): Boolean {
    val visible = rememberAiVisible(AiFeature.ASK)
    val ai = rememberAi()
    return visible || ai.demo
}

/**
 * In Search, under what is typed: when the words look like a question, a row that asks it. Tapping it opens the Ask Zyke page with the
 * question already asked. The model only turns the question into a request from a fixed list; the figures are worked out by the app
 * from the messages, the way every other total is.
 */
@Composable
fun AskEntry(query: String, messages: List<ClassifiedMessage>, cycle: CycleRule, onOpen: (SearchHit) -> Unit) {
    if (!rememberAiVisible(AiFeature.ASK)) return
    val trimmed = query.trim()
    if (trimmed.length < MIN_QUESTION_LENGTH || ' ' !in trimmed) return
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    var open by rememberSaveable(trimmed) { mutableStateOf(false) }
    FintechCard(
        Modifier.padding(horizontal = Spacing.lg),
        onClick = if (availability.canGenerate) ({ AskChatSession.chat.queued = trimmed; open = true }) else null
    ) {
        RowTitle("Ask Zyke")
        Caption(
            if (availability.canGenerate) "“$trimmed”, answered on this phone from your messages"
            else "The model is not ready yet. See Settings, On-device AI.",
            Modifier.padding(top = Spacing.xs), maxLines = 3
        )
    }
    if (open) {
        AskZykePage(
            messages, cycle,
            onOpenMessage = { id -> onOpen(SearchHit(SearchKind.MESSAGE, id.toString(), "Payment", "", messageId = id)) },
            onClose = { open = false }
        )
    }
}
