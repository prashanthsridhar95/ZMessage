package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AskAnswer
import com.zyke.message.domain.ai.AskExecutor
import com.zyke.message.domain.ai.AskMetric
import com.zyke.message.domain.ai.AskOutcome
import com.zyke.message.domain.ai.AskResult
import com.zyke.message.domain.ai.AskText
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.search.SearchHit
import com.zyke.message.domain.search.SearchKind
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_QUESTION_LENGTH = 4
private const val SHOWN_PAYMENTS = 8
private const val MAX_PAYEES = 500

/** What the Ask sheet is doing. */
private sealed interface AskStage {
    data object Thinking : AskStage
    data class Answer(val result: AskResult, val text: AskText) : AskStage
    data class Plain(val message: String) : AskStage
}

/**
 * In Search, under what is typed: when the words look like a question, a row that asks it. The model only turns the question into
 * a request from a fixed list; the figures are worked out by the app from the messages, the way every other total is.
 */
@Composable
fun AskEntry(query: String, messages: List<ClassifiedMessage>, cycle: CycleRule, onOpen: (SearchHit) -> Unit) {
    if (!rememberAiVisible(AiFeature.ASK)) return
    val trimmed = query.trim()
    if (trimmed.length < MIN_QUESTION_LENGTH || ' ' !in trimmed) return
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    var open by rememberSaveable(trimmed) { mutableStateOf(false) }
    FintechCard(Modifier.padding(horizontal = Spacing.lg), onClick = if (availability.canGenerate) ({ open = true }) else null) {
        RowTitle("Ask your money")
        Caption(
            if (availability.canGenerate) "“$trimmed”, answered on this phone from your messages"
            else "The model is not ready yet. See Settings, On-device AI.",
            Modifier.padding(top = Spacing.xs), maxLines = 3
        )
    }
    if (open) AskSheet(trimmed, messages, cycle, onOpen) { open = false }
}

@Composable
private fun AskSheet(question: String, messages: List<ClassifiedMessage>, cycle: CycleRule, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(title = "Ask your money", onDismiss = onDismiss, subtitle = "Answered on this phone from your messages") {
        AskResultPane(question, messages, cycle, onOpen, onDismiss)
    }
}

/**
 * The answer to [question]: asks the model to read it, the app works the figures out, and the payments behind are tappable. Shared by the Search sheet
 * and by Home's Ask AI sheet, so there is one parser, one executor and one answer layout.
 */
@Composable
internal fun AskResultPane(question: String, messages: List<ClassifiedMessage>, cycle: CycleRule, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    val ai = rememberAi()
    var stage by remember { mutableStateOf<AskStage>(AskStage.Thinking) }
    LaunchedEffect(question) {
        stage = AskStage.Thinking
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val payees = withContext(Dispatchers.Default) {
            messages.filter { it.content is MessageContent.Transaction }.groupingBy { it.merchant.name }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotBlank() }.take(MAX_PAYEES)
        }
        stage = when (val outcome = ai.ask(question, payees, today)) {
            is AskOutcome.Understood -> withContext(Dispatchers.Default) {
                val result = AskExecutor.run(outcome.query, messages, today, zone, cycle, ai.customNames())
                AskStage.Answer(result, AskAnswer.of(result, zone))
            }
            is AskOutcome.Refused -> AskStage.Plain(outcome.reason + ". Edit the question, or use the normal search results.")
            is AskOutcome.Failed -> AskStage.Plain(failureText(outcome.kind) + " The normal search results are still below.")
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        when (val s = stage) {
            AskStage.Thinking -> Caption("Understanding your question on this phone. This takes a few seconds.", maxLines = 3)
            is AskStage.Plain -> {
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                AppButton("Use normal search", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            }
            is AskStage.Answer -> AnswerBody(s.result, s.text, onOpen, onDismiss)
        }
        Caption("The AI only read your question. The figures come from your messages, worked out by the app, not by the AI.", maxLines = 4)
    }
}

@Composable
private fun AnswerBody(result: AskResult, text: AskText, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    FintechCard {
        Caption(text.title.uppercase(), maxLines = 2)
        text.figure?.let { figure ->
            if (result.query.metric == AskMetric.COUNT) Text(figure, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            else PrivateAmount(figure, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, fit = true)
        }
        Text(text.sentence, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
        Caption("Read as: " + text.understood.joinToString(" · "), Modifier.padding(top = Spacing.sm), maxLines = 3)
    }
    var showAll by rememberSaveable { mutableStateOf(false) }
    if (result.matches.isNotEmpty()) {
        Text("PAYMENTS", Modifier.heading(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        val shown = if (showAll) result.matches else result.matches.take(SHOWN_PAYMENTS)
        shown.forEach { m -> PaymentRow(m) { onOpen(SearchHit(SearchKind.MESSAGE, m.id.toString(), m.merchant.name, "", messageId = m.id)); onDismiss() } }
        if (!showAll && result.matches.size > SHOWN_PAYMENTS) {
            AppButton("Show all ${result.matches.size}", { showAll = true }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
        }
    }
    Caption("Not what you meant? Edit the question, or use the normal search results.", maxLines = 3)
}

private val DayFormat = com.zyke.message.presentation.format.FormatCache.pattern("d MMM", java.util.Locale.getDefault())

@Composable
private fun PaymentRow(message: ClassifiedMessage, onClick: () -> Unit) {
    val tx = message.content as? MessageContent.Transaction
    val name = message.merchant.name.ifBlank { message.message.sender }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).tappable(onClick = onClick, role = Role.Button, onClickLabel = "Open the payment to $name"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Caption(DayFormat.format(Instant.ofEpochMilli(message.message.date).atZone(ZoneId.systemDefault())), maxLines = 1)
        }
        if (tx != null) PrivateAmount(DisplayFormatters.rupees(tx.amount), Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
