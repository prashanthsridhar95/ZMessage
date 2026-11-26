package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.ai.AiCategory
import com.zyke.message.domain.ai.AiFailure
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiTeach
import com.zyke.message.domain.ai.BatchItem
import com.zyke.message.domain.ai.CheckedReading
import com.zyke.message.domain.ai.ReadingKind
import com.zyke.message.domain.ai.Understanding
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.teach.LearnedTemplate
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Under a message the app did not understand: "Ask the AI on this phone". It shows only when Understand unclear messages is
 * switched on and the phone has the model. The answer opens in a sheet; nothing is saved until the person taps Use this, and
 * then it is saved as a format they taught (the same store as "Teach the app").
 */
@Composable
internal fun AiUnderstandRow(message: ClassifiedMessage, onUse: (LearnedTemplate) -> Unit, onTeachMyself: () -> Unit) {
    if (!rememberAiVisible(AiFeature.UNDERSTAND)) return
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    val kept by ai.kept.collectAsState()
    var open by rememberSaveable(message.id) { mutableStateOf(false) }
    val known = remember(message.id, kept) { ai.understander.known(message.message.sender, message.message.body, ai.customs()) }
    if (known is Understanding.Dismissed) return
    val ready = known is Understanding.Suggestion
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        AppButton(
            if (ready) "See the AI's suggestion" else "Ask the AI on this phone",
            { open = true }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT,
            enabled = ready || availability.canGenerate
        )
        if (!ready && !availability.canGenerate) Caption("The model is not ready yet. See Settings, On-device AI.", Modifier.padding(top = Spacing.xs), maxLines = 2)
    }
    if (open) AiSuggestionSheet(message, onUse, onTeachMyself) { open = false }
}

@Composable
private fun AiSuggestionSheet(message: ClassifiedMessage, onUse: (LearnedTemplate) -> Unit, onTeachMyself: () -> Unit, onDismiss: () -> Unit) {
    val ai = rememberAi()
    val haptics = rememberHaptics()
    var result by remember { mutableStateOf<Understanding?>(null) }
    LaunchedEffect(message.id) { result = ai.understand(message.message.sender, message.message.body) }
    val current = result
    val reading = (current as? Understanding.Suggestion)?.reading
    val taught = remember(reading) { reading?.let { AiTeach.template(message.message, it) } }
    val title = when {
        reading != null -> reading.kind.title
        current == null -> "Reading the message"
        else -> "No suggestion"
    }
    AdaptiveSheet(
        title = title,
        onDismiss = onDismiss,
        subtitle = "Worked out by the AI on this phone. Nothing leaves it.",
        footer = {
            if (reading != null && reading.kind.movesMoney) {
                ResponsiveRow {
                    AppButton(
                        "Use this", { haptics.confirm(); taught?.let { onUse(it.template) }; onDismiss() },
                        Modifier.cell(), enabled = taught != null
                    )
                    AppButton("Not right", { ai.dismiss(message.message.sender, message.message.body); onDismiss() }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                }
            } else if (current != null) {
                AppButton("Close", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            }
            if (current != null) AppButton("Teach it myself instead", { onTeachMyself(); onDismiss() }, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT)
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            when {
                current == null -> Caption("This takes a few seconds. You can close this and carry on.", maxLines = 3)
                reading != null -> SuggestionBody(message, reading, canSave = taught != null, keptCategory = taught?.keptCategory == true)
                current is Understanding.Failed -> Caption(failureText(current.kind), maxLines = 4)
                current is Understanding.NoSuggestion -> Caption(
                    "The AI could not find a payment here that the text backs up (${current.reason.lowercase()}). Nothing was changed.", maxLines = 5
                )
                else -> Caption("Nothing was changed.", maxLines = 2)
            }
        }
    }
}

@Composable
private fun SuggestionBody(message: ClassifiedMessage, reading: CheckedReading, canSave: Boolean, keptCategory: Boolean) {
    val body = message.message.body
    val mark = MaterialTheme.colorScheme.primary.copy(alpha = HIGHLIGHT_ALPHA)
    val text: AnnotatedString = remember(body, reading, mark) {
        buildAnnotatedString {
            append(body)
            reading.evidence.forEach { r ->
                if (r.first >= 0 && r.last < body.length) addStyle(SpanStyle(background = mark, fontWeight = FontWeight.Bold), r.first, r.last + 1)
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(Spacing.md)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Caption("Highlighted words are what the AI used", Modifier.padding(top = Spacing.sm), maxLines = 2)
    }
    Caption("The app would record", maxLines = 1)
    Text(recordLine(reading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    val extras = listOfNotNull(
        reading.last4?.let { "Account ••$it" },
        reading.category?.let { "Category: ${it.name}" + if (it.builtIn == null || !keptCategory) " (not saved with the format)" else "" }
    )
    if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    if (reading.directionUnsure) Caption("The message talks about money going out and coming in. Check the direction.", maxLines = 3)
    Caption(
        when {
            !reading.kind.movesMoney -> "This looks like a bill, not a payment. Open the message to deal with it."
            canSave -> "Saved as a format you taught, only on this phone. Check it against the message before you use it."
            else -> "The AI's answer could not be made into a safe format for this message. You can teach it yourself."
        },
        maxLines = 4
    )
}

private fun recordLine(reading: CheckedReading): String {
    val money = reading.amount?.let { DisplayFormatters.rupees(it) }
    val who = reading.merchant
    return when (reading.kind) {
        ReadingKind.SPEND -> "Spent ${money.orEmpty()}" + (who?.let { " at $it" } ?: "")
        ReadingKind.INCOME -> "Received ${money.orEmpty()}" + (who?.let { " from $it" } ?: "")
        ReadingKind.REFUND -> "Refund of ${money.orEmpty()}" + (who?.let { " from $it" } ?: "")
        ReadingKind.BILL -> "Bill" + (money?.let { " of $it" } ?: "") + (who?.let { " from $it" } ?: "")
        ReadingKind.OTHER -> "Not a payment"
    }
}

internal fun failureText(kind: AiFailure): String = when (kind) {
    AiFailure.BUSY, AiFailure.QUOTA -> kind.plain + ". Try again in a little while."
    AiFailure.UNAVAILABLE -> kind.plain + ". See Settings, On-device AI."
    AiFailure.TIMEOUT -> "The phone took too long to answer. Nothing was changed. Try again."
    else -> kind.plain + ". Nothing was changed."
}

/** "Check my review pile": the messages the app did not understand, a few at a time. Used on that page and in Settings. */
@Composable
internal fun AiBatchControls(unclear: () -> List<ClassifiedMessage>) {
    val ai = rememberAi()
    val progress by ai.batchProgress.collectAsState()
    val scope = rememberCoroutineScope()
    val lite = ai.aiContext().lite
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Caption(
            when {
                lite -> "Turned off while the phone is in lite mode."
                progress.running -> "Checked ${progress.done} of ${progress.total}, ${progress.found} suggestions so far."
                progress.total > 0 -> "Checked ${progress.done} of ${progress.total}, ${progress.found} suggestions" + (progress.stoppedBecause?.let { ". $it." } ?: ".")
                else -> "Reads up to ${com.zyke.message.domain.ai.AiBatch.MAX_PER_RUN} messages one after another. Nothing is saved until you open one and tap Use this."
            },
            maxLines = 4
        )
        ResponsiveRow {
            AppButton(
                "Check now",
                {
                    scope.launch {
                        val items = withContext(Dispatchers.Default) { unclear().map { BatchItem(it.id, it.message.sender, it.message.body) } }
                        ai.startBatch(items)
                    }
                },
                Modifier.cell(), enabled = !lite && !progress.running
            )
            AppButton("Stop", { ai.stopBatch() }, Modifier.cell(), style = ButtonStyle.OUTLINE, enabled = progress.running)
        }
    }
}

/** In the category sheet, for a payee with no category: ask the AI for an idea. The person still taps the category to file it. */
@Composable
internal fun AiCategoryIdea(payee: String, available: List<MessageCategory>, onPick: (AiCategory) -> Unit) {
    if (!rememberAiVisible(AiFeature.CATEGORIES)) return
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    var idea by remember(payee) { mutableStateOf<AiCategory?>(null) }
    var asked by remember(payee) { mutableStateOf(false) }
    var busy by remember(payee) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val shown = idea
        if (shown != null && (shown.builtIn == null || shown.builtIn in available)) {
            AppButton("AI idea: file under ${shown.name}", { onPick(shown) }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            Caption("From the AI on this phone, looking only at the name. It can be wrong; tap to file it, or choose another.", maxLines = 3)
        } else {
            AppButton(
                if (busy) "Asking" else "Ask the AI for an idea",
                { busy = true; scope.launch { idea = ai.categoryIdea(payee); asked = true; busy = false } },
                Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, enabled = availability.canGenerate && !busy
            )
            if (asked && idea == null) Caption("The AI had no idea for this name.", maxLines = 2)
        }
    }
}

/** Under the list of messages not understood: check several with the AI at once (when it is switched on and the model is ready). */
@Composable
internal fun AiReviewPileRow(unclear: List<ClassifiedMessage>) {
    if (!rememberAiVisible(AiFeature.UNDERSTAND)) return
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    if (!availability.canGenerate) return
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Text("Check these with the AI", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        AiBatchControls { unclear }
    }
}
