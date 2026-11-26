package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.search.SearchHit
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.common.LocalOpenSearch
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing

private const val MIN_QUESTION_LENGTH = 4
private val Suggestions = listOf("This month's spending", "Biggest payment this month", "Food last week")

/** Whether Home offers Ask AI: the master switch and "Ask your money" are on, this is not Demo, and the phone can do it at all. */
@Composable
fun rememberAskHomeVisible(): Boolean = rememberAiVisible(AiFeature.ASK)

/**
 * Home's Ask AI sheet: a field, three suggestions and the same answer as Search's "Ask your money" ([AskResultPane]: one parser, one executor,
 * tappable payments). When the model is not ready, or the question is not understood, the plain way out is the normal search.
 */
@Composable
fun AskMoneySheet(messages: List<ClassifiedMessage>, cycle: CycleRule, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    val ready = availability.canGenerate || ai.allowed(AiFeature.ASK)
    var draft by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf("") }
    val openSearch = LocalOpenSearch.current
    val openSettings = LocalOpenSettings.current
    AdaptiveSheet(title = "Ask AI", onDismiss = onDismiss, subtitle = "Answered on this phone from your messages") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            FormField(
                "Ask about your money", draft, { draft = it },
                placeholder = "How much did I spend on food last week?", imeAction = ImeAction.Done
            )
            Chips(Suggestions.map { ChipOption(it, it == asked) }, onSelect = { index -> draft = Suggestions[index]; asked = Suggestions[index] })
            AppButton("Ask", { asked = draft.trim() }, Modifier.fillMaxWidth(), enabled = ready && draft.trim().length >= MIN_QUESTION_LENGTH)
            if (!ready) {
                Caption("The model is not ready yet, so the answer cannot be worked out.", maxLines = 3)
                LinkButton("Set up on-device AI", { onDismiss(); openSettings() })
                AppButton("Use normal search", { onDismiss(); openSearch() }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            } else if (asked.length >= MIN_QUESTION_LENGTH) {
                AskResultPane(asked, messages, cycle, onOpen, onDismiss)
            }
        }
    }
}
