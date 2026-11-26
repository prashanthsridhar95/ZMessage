package com.zyke.message.presentation.ai

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zyke.message.domain.ai.AiMonth
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.dashboard.components.InsightCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing
import java.time.ZoneId

/**
 * Insights: "This month in a sentence". The app's own sentence is there at once. If the model is ready, its sentence replaces
 * it when it arrives, but only if it uses no figure the app did not work out (AiMonth.verify); otherwise the app's stays.
 */
@Composable
internal fun MonthSentenceCard(summary: MonthlySummary, messages: List<ClassifiedMessage>) {
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    val facts = remember(summary, messages) { AiMonth.facts(summary, messages, ZoneId.systemDefault()) } ?: return
    var sentence by remember(facts.key) { mutableStateOf(ai.knownMonthSentence(facts)) }
    var plain by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(facts.key, availability.canGenerate) {
        if (sentence == null && availability.canGenerate) sentence = ai.monthSentence(facts)
    }
    val aiText = sentence
    InsightCard("This month in a sentence", subtitle = "Written on this phone from your own figures", summary = "On-device AI") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(if (aiText != null && !plain) aiText else facts.plain, style = MaterialTheme.typography.bodyLarge)
            Caption(
                if (aiText != null && !plain) "By the AI on this phone. Every figure was checked against your own numbers."
                else if (aiText != null) "The app's own sentence."
                else "The app's own sentence. The AI's version appears here when it is ready.",
                maxLines = 3
            )
            if (aiText != null) {
                AppButton(
                    if (plain) "Show the AI's version" else "Show the plain version", { plain = !plain },
                    Modifier.fillMaxWidth().padding(top = Spacing.xs), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
                )
            }
        }
    }
}
