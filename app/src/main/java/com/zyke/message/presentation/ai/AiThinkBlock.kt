package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zyke.message.domain.ai.Phrased
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What one "ask the AI" block is doing. */
private sealed interface Think {
    data object Idle : Think
    data object Thinking : Think
    data class Done(val text: String) : Think
    data class Problem(val text: String) : Think
}

/**
 * The one shape of an on-demand AI answer: a button, then "Thinking" in words with a Cancel (no spinner; the request waits its turn in the
 * single request line), then the checked text with a note on where it came from, or the reason there is none. Cancelling, leaving the
 * screen or changing [key] stops the request. Nothing is applied: with [onUse] the text is offered on a button the person taps.
 *
 * @param known the checked text if it is already stored (shown at once, no request)
 * @param ready whether a request can be answered now; when not, the button is off and says why
 * @param ask runs the request; the result has already passed the feature's guard
 * @param onUse when set, the answer is a suggestion for something the person edits: the button [useLabel] gives it back to the caller
 */
@Composable
internal fun AiThinkBlock(
    key: Any?,
    startLabel: String,
    known: String?,
    ready: Boolean,
    ask: suspend () -> Phrased,
    footnote: String,
    modifier: Modifier = Modifier,
    useLabel: (String) -> String = { it },
    onUse: ((String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var think by remember(key) { mutableStateOf<Think>(if (known != null) Think.Done(known) else Think.Idle) }
    var job by remember(key) { mutableStateOf<Job?>(null) }
    // An answer that arrives from the store while nothing is being asked shows at once.
    LaunchedEffect(known) { if (known != null && think == Think.Idle) think = Think.Done(known) }

    fun start() {
        think = Think.Thinking
        job = scope.launch {
            think = when (val r = ask()) {
                is Phrased.Ok -> Think.Done(r.text)
                is Phrased.Rejected -> Think.Problem("The AI's answer did not match the facts, so it is not shown. Nothing was changed.")
                is Phrased.Failed -> Think.Problem(failureText(r.kind))
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (val t = think) {
            Think.Idle -> {
                AppButton(startLabel, { start() }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = ready)
                if (!ready) Caption("The model is not ready yet. See Settings, On-device AI.", maxLines = 2)
            }
            Think.Thinking -> {
                Caption("Thinking… this takes a few seconds on this phone.", maxLines = 2)
                AppButton("Cancel", { job?.cancel(); job = null; think = Think.Idle }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            }
            is Think.Done -> {
                if (onUse == null) {
                    Text(t.text, style = MaterialTheme.typography.bodyMedium)
                } else {
                    AppButton(useLabel(t.text), { onUse(t.text); think = Think.Idle }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
                    AppButton("Not this one", { think = Think.Idle }, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
                Caption(footnote, maxLines = 4)
            }
            is Think.Problem -> {
                Caption(t.text, maxLines = 4)
                AppButton("Try again", { start() }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = ready)
            }
        }
    }
}
