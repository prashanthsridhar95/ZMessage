package com.zyke.message.presentation.email

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.email.EmailPrompt
import com.zyke.message.domain.email.EmailPromptKind
import com.zyke.message.domain.email.EmailPromptPhase
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.OnboardingHero
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing

/**
 * The first-run page (fresh install) and the one-time "New" page (after an update) for email alerts, in the one onboarding layout, modelled on
 * the spending places page. It switches nothing on by itself: "Set up" leads to the plain consent page, its "Continue" only records that
 * the question was answered and shows the same connect form as Settings, and the feature is on only after that form's Connect succeeds
 * (`EmailSync.connect`). "Not now", the back button and a swipe away all mean "leave it off, and do not ask again"; Settings keeps the
 * entry. Never shown in demo mode (the caller decides with [EmailPrompt.kind]).
 */
@Composable
fun EmailPromptHost(kind: EmailPromptKind, onDone: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repo = container.emailAlerts
    val state by repo.state.collectAsStateWithLifecycle()
    var phase by rememberSaveable { mutableIntStateOf(EmailPromptPhase.OFFER.ordinal) }
    val current = EmailPromptPhase.entries[phase]

    fun answered() = repo.update { it.copy(emailPrompted = true) }
    fun notNow() { answered(); onDone() }

    Dialog(
        onDismissRequest = { notNow() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (current) {
                EmailPromptPhase.OFFER -> OnboardingHero(
                    icon = com.zyke.message.R.drawable.baseline_message_24,
                    headline = EmailPrompt.headline(kind),
                    promise = EmailPrompt.PROMISE,
                    primaryLabel = EmailPrompt.SET_UP,
                    onPrimary = { phase = EmailPromptPhase.CONSENT.ordinal },
                    secondaryLabel = EmailPrompt.NOT_NOW,
                    onSecondary = { notNow() },
                    secondaryStyle = ButtonStyle.OUTLINE,
                    footnote = EmailPrompt.FOOTNOTE,
                    extra = { Points(EmailPrompt.POINTS) }
                )
                EmailPromptPhase.CONSENT -> OnboardingHero(
                    icon = com.zyke.message.R.drawable.baseline_message_24,
                    headline = EmailPrompt.CONSENT_HEADLINE,
                    promise = EmailPrompt.CONSENT_PROMISE,
                    primaryLabel = EmailPrompt.CONTINUE,
                    // Continue after reading the consent page is the answer; it still switches nothing on.
                    onPrimary = { repo.update { it.copy(emailPrompted = true, explained = true) }; phase = EmailPromptPhase.CONNECT.ordinal },
                    secondaryLabel = EmailPrompt.NOT_NOW,
                    onSecondary = { notNow() },
                    secondaryStyle = ButtonStyle.OUTLINE,
                    extra = { Points(EmailPrompt.CONSENT_POINTS) }
                )
                EmailPromptPhase.CONNECT -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Text(
                        EmailPrompt.CONNECT_HEADLINE, Modifier.heading(), style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ConnectCard(state.settings, container.emailSync, onConnected = onDone)
                    AppButton("Not now", { onDone() }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
                }
            }
        }
    }
}

/** The short list under the promise: plain bullets that wrap, so a large font or a 320 dp screen only makes the page scroll. */
@Composable
private fun Points(points: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        points.forEach { Caption("•  $it", maxLines = 6, color = MaterialTheme.colorScheme.secondary) }
    }
}
